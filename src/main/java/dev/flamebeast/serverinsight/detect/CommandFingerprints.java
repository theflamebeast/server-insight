package dev.flamebeast.serverinsight.detect;

import java.util.Locale;
import java.util.Map;

/**
 * Maps well-known command names to the plugin that registers them.
 *
 * This is the cheapest detection the mod has: the command tree already arrived on join,
 * so recognising "/lp" as LuckPerms costs no packets at all. It catches plugins the
 * namespace scan misses entirely — a plugin only shows up there if the server exposes
 * its commands namespaced, which many do not.
 *
 * These are INFERENCES and the output has to mark them as such. A command name is not
 * proof: "/tab" or "/npc" could be anything, and a server can register whatever it
 * likes. Anything found here is reported at lower confidence than a namespace or a
 * tab-completion hit.
 */
public final class CommandFingerprints {
	private CommandFingerprints() {
	}

	private static final Map<String, String> BY_COMMAND = Map.ofEntries(
		Map.entry("lp", "LuckPerms"),
		Map.entry("luckperms", "LuckPerms"),
		Map.entry("co", "CoreProtect"),
		Map.entry("coreprotect", "CoreProtect"),
		Map.entry("we", "WorldEdit"),
		Map.entry("worldedit", "WorldEdit"),
		Map.entry("wg", "WorldGuard"),
		Map.entry("worldguard", "WorldGuard"),
		Map.entry("mv", "Multiverse"),
		Map.entry("mvtp", "Multiverse"),
		Map.entry("ess", "EssentialsX"),
		Map.entry("essentials", "EssentialsX"),
		Map.entry("dynmap", "dynmap"),
		Map.entry("tebex", "Tebex"),
		Map.entry("buycraft", "Tebex"),
		Map.entry("discordsrv", "DiscordSRV"),
		Map.entry("via", "ViaVersion"),
		Map.entry("viaversion", "ViaVersion"),
		Map.entry("plugman", "PlugMan"),
		Map.entry("plugmanx", "PlugManX"),
		Map.entry("skript", "Skript"),
		Map.entry("papi", "PlaceholderAPI"),
		Map.entry("placeholderapi", "PlaceholderAPI"),
		Map.entry("citizens", "Citizens"),
		Map.entry("npc", "Citizens"),
		Map.entry("dh", "DecentHolograms"),
		Map.entry("decentholograms", "DecentHolograms"),
		Map.entry("grim", "GrimAC"),
		Map.entry("grimac", "GrimAC"),
		Map.entry("vulcan", "Vulcan"),
		Map.entry("spartan", "Spartan"),
		Map.entry("themis", "Themis"),
		Map.entry("totemguard", "TotemGuard"),
		Map.entry("ncp", "NoCheatPlus"),
		Map.entry("nocheatplus", "NoCheatPlus"),
		Map.entry("cmi", "CMI"),
		Map.entry("tab", "TAB"),
		Map.entry("vault", "Vault"),
		Map.entry("litebans", "LiteBans"),
		Map.entry("advancedban", "AdvancedBan"),
		Map.entry("authme", "AuthMe"),
		Map.entry("chunky", "Chunky"),
		Map.entry("griefprevention", "GriefPrevention"),
		Map.entry("huskhomes", "HuskHomes"),
		Map.entry("headdb", "HeadDatabase"),
		Map.entry("shop", "ShopGUIPlus"),
		Map.entry("ah", "AuctionHouse"),
		Map.entry("crates", "ExcellentCrates")
	);

	/**
	 * The plugin a root command node implies, or null.
	 *
	 * @param commandName a root node name, namespaced ("luckperms:lp") or not ("lp")
	 */
	public static String pluginFor(String commandName) {
		if (commandName == null) {
			return null;
		}

		String name = commandName.toLowerCase(Locale.ROOT);

		// A namespaced node already tells the namespace scan everything; match on the
		// bare command so "luckperms:lp" and "lp" both resolve.
		int colon = name.indexOf(':');
		if (colon >= 0) {
			name = name.substring(colon + 1);
		}

		return BY_COMMAND.get(name);
	}
}
