package dev.flamebeast.serverinsight.detect;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Locale;

/**
 * Where a server is hosted, as reported by the geolocation lookup.
 *
 * Every field is best-effort: the lookup answers for the IP the hostname resolves to,
 * which for anything behind a proxy, CDN or anycast network is the edge, not the
 * machine running the game. The output has to present this as "where the address
 * points", not "where the server is".
 *
 * @param countryCode lowercase ISO 3166-1 alpha-2, which is also the flag texture name
 */
public record LocationInfo(
	String countryCode,
	String countryName,
	String regionName,
	String cityName,
	String isp,
	String org,
	String asName,
	String timezone,
	String queriedIp
) {
	/**
	 * Parses one geolocation response. Pure and side-effect free so it can be tested
	 * without touching the network.
	 *
	 * @return the parsed location, or null if the response was malformed or reported a
	 *         failed lookup (a private address, a reserved range, an unknown host)
	 */
	public static LocationInfo fromJson(String json) {
		if (json == null || json.isBlank()) {
			return null;
		}

		try {
			if (!(JsonParser.parseString(json) instanceof JsonObject object)) {
				return null;
			}

			// The API answers 200 with {"status":"fail"} for private ranges and bad
			// hosts, so the body is the only place a failure shows up.
			if (!object.has("status") || !"success".equals(string(object, "status"))) {
				return null;
			}

			String countryCode = string(object, "countryCode");
			if (countryCode == null || countryCode.length() != 2) {
				return null;
			}

			return new LocationInfo(
				countryCode.toLowerCase(Locale.ROOT),
				string(object, "country"),
				string(object, "regionName"),
				string(object, "city"),
				string(object, "isp"),
				string(object, "org"),
				string(object, "as"),
				string(object, "timezone"),
				string(object, "query")
			);
		} catch (Exception malformed) {
			return null;
		}
	}

	private static String string(JsonObject object, String key) {
		if (!object.has(key) || object.get(key).isJsonNull()) {
			return null;
		}

		String value = object.get(key).getAsString();
		return value.isBlank() ? null : value;
	}

	/** "Frankfurt, Hesse, Germany", skipping whatever the lookup did not return. */
	public String describePlace() {
		StringBuilder out = new StringBuilder();

		for (String part : new String[]{cityName, regionName, countryName}) {
			if (part == null) {
				continue;
			}

			if (!out.isEmpty()) {
				out.append(", ");
			}

			out.append(part);
		}

		return out.isEmpty() ? countryCode.toUpperCase(Locale.ROOT) : out.toString();
	}
}
