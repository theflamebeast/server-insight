package dev.flamebeast.serverinsight.detect;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classification of the server's brand string.
 *
 * The raw brand is a free-form token the server picks — "vanilla", "Paper", "fabric",
 * "BungeeCord (git:...)". On its own it means nothing to most users; what they want to
 * know is whether this server can even run plugins, which is what {@link Family}
 * answers. It also tells the plugin scanner's output what to say when it finds
 * nothing: on a vanilla or modded server, finding no plugins is the correct answer
 * rather than a sign the server is hiding them.
 *
 * @param name    display name, e.g. "Paper"
 * @param family  what kind of server software this is
 * @param version version pulled out of the brand, or null if it carried none
 * @param proxy   proxy the connection passes through, or null
 */
public record ServerSoftware(String name, Family family, String version, String proxy) {
	public enum Family {
		/** Bukkit lineage — the only family with a plugin system. */
		BUKKIT("plugins supported"),
		/** Fabric, Quilt, Forge, NeoForge. Mods, not plugins. */
		MODDED("modded, no plugins"),
		VANILLA("vanilla, no plugins"),
		PROXY("proxy"),
		UNKNOWN(null);

		private final String hint;

		Family(String hint) {
			this.hint = hint;
		}

		public String hint() {
			return hint;
		}
	}

	/** Longest-first, so "craftbukkit" is not swallowed by "bukkit" and "neoforge" not by "forge". */
	private static final String[][] KNOWN = {
		{"folia", "Folia", "BUKKIT"},
		{"purpur", "Purpur", "BUKKIT"},
		{"pufferfish", "Pufferfish", "BUKKIT"},
		{"airplane", "Airplane", "BUKKIT"},
		{"tuinity", "Tuinity", "BUKKIT"},
		{"paper", "Paper", "BUKKIT"},
		{"spigot", "Spigot", "BUKKIT"},
		{"craftbukkit", "CraftBukkit", "BUKKIT"},
		{"bukkit", "Bukkit", "BUKKIT"},
		{"neoforge", "NeoForge", "MODDED"},
		{"forge", "Forge", "MODDED"},
		{"quilt", "Quilt", "MODDED"},
		{"fabric", "Fabric", "MODDED"},
		{"vanilla", "Vanilla", "VANILLA"},
	};

	private static final String[][] PROXIES = {
		{"bungeecord", "BungeeCord"},
		{"waterfall", "Waterfall"},
		{"velocity", "Velocity"},
	};

	/** Matches a leading version-looking token, e.g. the "1.21.4" in "Paper 1.21.4-123". */
	private static final Pattern VERSION = Pattern.compile("\\b(\\d+\\.\\d+(?:\\.\\d+)?)\\b");

	public static ServerSoftware parse(String brand) {
		if (brand == null || brand.isBlank()) {
			return new ServerSoftware("unknown", Family.UNKNOWN, null, null);
		}

		String lower = brand.toLowerCase(Locale.ROOT);

		String proxy = null;
		for (String[] entry : PROXIES) {
			if (lower.contains(entry[0])) {
				proxy = entry[1];
				break;
			}
		}

		for (String[] entry : KNOWN) {
			if (lower.contains(entry[0])) {
				return new ServerSoftware(entry[1], Family.valueOf(entry[2]), version(brand), proxy);
			}
		}

		// A proxy that rewrote the brand entirely, so the backend is unknowable from here.
		if (proxy != null) {
			return new ServerSoftware(proxy, Family.PROXY, version(brand), null);
		}

		// Unrecognised brands are usually forks or custom software; show it verbatim
		// rather than pretending we know what it is.
		return new ServerSoftware(brand, Family.UNKNOWN, version(brand), null);
	}

	private static String version(String brand) {
		Matcher matcher = VERSION.matcher(brand);
		return matcher.find() ? matcher.group(1) : null;
	}

	/** Name plus version when the brand carried one. */
	public String displayName() {
		return version == null ? name : name + " " + version;
	}
}
