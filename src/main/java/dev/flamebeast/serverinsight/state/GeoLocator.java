package dev.flamebeast.serverinsight.state;

import dev.flamebeast.serverinsight.ServerInsightClient;
import dev.flamebeast.serverinsight.detect.LocationInfo;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Looks up where a server address is hosted, asynchronously and at most once per host.
 *
 * This is the only part of the mod that talks to anything other than the Minecraft
 * server, and it is worth being deliberate about: each lookup tells a third party that
 * somebody is interested in that address. So it only ever runs for addresses the user
 * is actually looking at, and every answer is cached — a final failure for the session,
 * a transient one for RETRY_AFTER_MILLIS — so nothing is requested from a render loop
 * more than once.
 *
 * The free geolocation endpoint allows 45 requests per minute per client and answers
 * 429 past that, so requests are throttled below the limit and a 429 parks everything
 * until the cooldown expires. The server list can hold far more entries than the quota.
 */
public final class GeoLocator {
	private GeoLocator() {
	}

	/** Plain HTTP: the free tier of this endpoint does not serve HTTPS. */
	private static final String ENDPOINT = "http://ip-api.com/json/";

	private static final String FIELDS =
		"?fields=status,message,country,countryCode,regionName,city,isp,org,as,timezone,query,lat,lon";

	private static final int MAX_REQUESTS_PER_WINDOW = 40;
	private static final long WINDOW_MILLIS = 60_000L;

	/** How long a transient failure (offline, timeout, 429, captive portal) parks a host. */
	private static final long RETRY_AFTER_MILLIS = 60_000L;

	/**
	 * Lowercased hostnames and IP literals only. The host is spliced into the request
	 * path, so anything else (a slash, a query, whitespace) never reaches the URL.
	 */
	private static final Pattern HOST_SHAPE = Pattern.compile("[a-z0-9.:\\-]+");
	private static final Pattern NUMERIC_SHAPE = Pattern.compile("[0-9.]+");

	/** Suffixes that only ever name machines on someone's own network. */
	private static final Set<String> LAN_SUFFIXES = Set.of(".local", ".localhost", ".lan", ".home.arpa", ".internal");

	/** The HttpClient's own executor does the waiting, not the shared ForkJoin pool. */
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(5))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	private static final Map<String, LocationInfo> CACHE = new ConcurrentHashMap<>();
	private static final Set<String> UNPLACEABLE = ConcurrentHashMap.newKeySet();
	private static final Map<String, Long> RETRY_AT = new ConcurrentHashMap<>();
	private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

	/** Empty key: the endpoint answers a request with no host with the caller's own location. */
	private static final String SELF = "";

	/** Timestamps of recent requests, for the sliding-window throttle. */
	private static final Deque<Long> RECENT = new ArrayDeque<>();

	private static volatile long cooldownUntilMillis = 0L;

	/** What one reply means for the cache. */
	public enum Outcome {
		FOUND,
		/** A final answer: the address cannot be placed. Never asked again this session. */
		UNPLACEABLE,
		/** Says nothing about the address. Asked again after RETRY_AFTER_MILLIS. */
		RETRY_LATER
	}

	public record Reply(Outcome outcome, LocationInfo location) {
	}

	/**
	 * Cached location for a host, kicking off a lookup if this is the first time it has
	 * been asked for.
	 *
	 * Safe to call every frame — that is the point. Returns null while the lookup is in
	 * flight, for a host that can never be placed, and for one parked after a transient
	 * failure until its retry time comes round.
	 */
	public static LocationInfo lookup(String host) {
		if (!isLookupEligible(host)) {
			return null;
		}

		return cachedOrRequest(host.toLowerCase(Locale.ROOT));
	}

	/**
	 * The client's own location, for the distance shown in the location hover.
	 *
	 * The only way to get it is to ask the same endpoint "where is me", which necessarily
	 * reports the user's own IP to it — worth knowing, since this is otherwise a purely
	 * passive mod. Cached like any other host, so it goes out once per session.
	 */
	public static LocationInfo localLocation() {
		return cachedOrRequest(SELF);
	}

	private static LocationInfo cachedOrRequest(String key) {
		LocationInfo cached = CACHE.get(key);
		if (cached != null) {
			return cached;
		}

		if (UNPLACEABLE.contains(key) || IN_FLIGHT.contains(key)) {
			return null;
		}

		Long retryAt = RETRY_AT.get(key);
		if (retryAt != null && System.currentTimeMillis() < retryAt) {
			return null;
		}

		if (!claimRequestSlot() || !IN_FLIGHT.add(key)) {
			return null;
		}

		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(ENDPOINT + key + FIELDS))
			.timeout(Duration.ofSeconds(8))
			.header("User-Agent", "ServerInsight")
			.GET()
			.build();

		HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
			.handle((response, error) -> {
				// Offline, DNS failure, timeout, firewall: all transient, all the same here.
				Reply reply = error != null
					? new Reply(Outcome.RETRY_LATER, null)
					: interpret(response.statusCode(), response.body());

				if (error == null && response.statusCode() == 429) {
					startCooldown(response);
				}

				record(key, reply);
				return null;
			});

		return null;
	}

	private static void record(String key, Reply reply) {
		switch (reply.outcome()) {
			case FOUND -> {
				CACHE.put(key, reply.location());
				RETRY_AT.remove(key);
			}
			case UNPLACEABLE -> UNPLACEABLE.add(key);
			case RETRY_LATER -> RETRY_AT.put(key, System.currentTimeMillis() + RETRY_AFTER_MILLIS);
		}

		IN_FLIGHT.remove(key);
	}

	/**
	 * Classifies one HTTP reply. Pure, so the gametest checks it without the network.
	 * Only a well-formed "fail" reply is final: a non-200, or a 200 whose body isn't the
	 * API's at all, says nothing about the address.
	 */
	public static Reply interpret(int statusCode, String body) {
		if (statusCode != 200) {
			return new Reply(Outcome.RETRY_LATER, null);
		}

		LocationInfo location = LocationInfo.fromJson(body);
		if (location != null) {
			return new Reply(Outcome.FOUND, location);
		}

		return new Reply(LocationInfo.isDefinitiveFailure(body) ? Outcome.UNPLACEABLE : Outcome.RETRY_LATER, null);
	}

	/** Past quota: park every lookup rather than hammering a limiter that is already saying no. */
	private static void startCooldown(HttpResponse<String> response) {
		long retryAfter = response.headers().firstValue("X-Ttl")
			.map(value -> parseSeconds(value) * 1000L)
			.orElse(WINDOW_MILLIS);
		cooldownUntilMillis = System.currentTimeMillis() + retryAfter;
		ServerInsightClient.LOGGER.warn("Geolocation rate limited, pausing lookups for {} ms", retryAfter);
	}

	private static long parseSeconds(String value) {
		try {
			return Math.max(1L, Long.parseLong(value.trim()));
		} catch (NumberFormatException e) {
			return WINDOW_MILLIS / 1000L;
		}
	}

	/** False when the throttle or an active cooldown says this request must not go out. */
	private static synchronized boolean claimRequestSlot() {
		long now = System.currentTimeMillis();

		if (now < cooldownUntilMillis) {
			return false;
		}

		while (!RECENT.isEmpty() && now - RECENT.peekFirst() > WINDOW_MILLIS) {
			RECENT.removeFirst();
		}

		if (RECENT.size() >= MAX_REQUESTS_PER_WINDOW) {
			return false;
		}

		RECENT.addLast(now);
		return true;
	}

	/**
	 * Whether a host may be sent to the geolocation service at all.
	 *
	 * Private, loopback and link-local addresses are refused: a LAN address cannot be
	 * placed, so sending one only leaks the shape of someone's home network. Ranges are
	 * checked on the PARSED address and only for IP literals — matching string prefixes
	 * against hostnames is what made fdcraft.net look like an IPv6 unique-local address.
	 * A hostname that resolves to a LAN address cannot be caught without DNS, which must
	 * not happen on the render path; the API answers "fail" and it is cached for good.
	 */
	public static boolean isLookupEligible(String host) {
		if (host == null) {
			return false;
		}

		String h = host.toLowerCase(Locale.ROOT);
		if (h.isEmpty() || !HOST_SHAPE.matcher(h).matches() || h.equals("localhost")) {
			return false;
		}

		for (String suffix : LAN_SUFFIXES) {
			if (h.endsWith(suffix)) {
				return false;
			}
		}

		boolean looksLikeIp = h.contains(":") || NUMERIC_SHAPE.matcher(h).matches();
		if (!looksLikeIp) {
			return true;
		}

		InetAddress ip;
		try {
			ip = InetAddress.ofLiteral(h);
		} catch (IllegalArgumentException notAnAddress) {
			return false;
		}

		return !(ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress()
			|| ip.isSiteLocalAddress() || isUniqueLocal(ip) || isCarrierGradeNat(ip) || isThisNetwork(ip));
	}

	/** fc00::/7 — the IPv6 counterpart of 10/8, which isSiteLocalAddress() does not cover. */
	private static boolean isUniqueLocal(InetAddress ip) {
		return ip instanceof Inet6Address && (ip.getAddress()[0] & 0xFE) == 0xFC;
	}

	/** 100.64.0.0/10, shared address space behind carrier NAT. */
	private static boolean isCarrierGradeNat(InetAddress ip) {
		byte[] b = ip.getAddress();
		return ip instanceof Inet4Address && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
	}

	/** 0.0.0.0/8. */
	private static boolean isThisNetwork(InetAddress ip) {
		return ip instanceof Inet4Address && ip.getAddress()[0] == 0;
	}
}
