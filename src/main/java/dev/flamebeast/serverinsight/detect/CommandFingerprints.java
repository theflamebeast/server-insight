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
 * proof: a server can register whatever it likes. Anything found here is reported at
 * lower confidence than a namespace or a tab-completion hit.
 *
 * Two rules for adding entries:
 *
 * 1. **The command must identify ONE plugin.** Generic commands that a dozen plugins
 *    register — /shop, /ah, /crates, /vanish, /eco, /gm — are the tempting ones and the
 *    wrong ones: naming a specific plugin off a generic command is confidently wrong
 *    output, which is worse than reporting nothing. (/shop, /ah and /crates were in the
 *    first version of this table for exactly that bad reason and have been removed.)
 * 2. **Never add a vanilla command.** /worldborder, /time, /weather, /perf, /tick,
 *    /version and friends exist on every server and would fingerprint every server.
 *
 * Keys are sorted so duplicates are visible — Map.ofEntries throws on a duplicate key,
 * which would take the whole mod down at class-load, and the gametest exercises this
 * class on every run so CI catches it.
 */
public final class CommandFingerprints {
	private CommandFingerprints() {
	}

	private static final Map<String, String> BY_COMMAND = Map.ofEntries(
		Map.entry("aac", "AAC"),
		Map.entry("abandonclaim", "GriefPrevention"),
		Map.entry("advancedban", "AdvancedBan"),
		Map.entry("authme", "AuthMe"),
		Map.entry("bentobox", "BentoBox"),
		Map.entry("bluemap", "BlueMap"),
		Map.entry("buycraft", "Tebex"),
		Map.entry("chatty", "Chatty"),
		Map.entry("chunky", "Chunky"),
		Map.entry("citizens", "Citizens"),
		Map.entry("cmi", "CMI"),
		Map.entry("co", "CoreProtect"),
		Map.entry("coreprotect", "CoreProtect"),
		Map.entry("crazycrates", "CrazyCrates"),
		Map.entry("decentholograms", "DecentHolograms"),
		Map.entry("deluxemenus", "DeluxeMenus"),
		Map.entry("dh", "DecentHolograms"),
		Map.entry("discordsrv", "DiscordSRV"),
		Map.entry("dynmap", "dynmap"),
		Map.entry("ess", "EssentialsX"),
		Map.entry("essentials", "EssentialsX"),
		Map.entry("excellentcrates", "ExcellentCrates"),
		Map.entry("factions", "Factions"),
		Map.entry("fawe", "FastAsyncWorldEdit"),
		Map.entry("floodgate", "Floodgate"),
		Map.entry("geyser", "Geyser"),
		Map.entry("griefprevention", "GriefPrevention"),
		Map.entry("grim", "GrimAC"),
		Map.entry("grimac", "GrimAC"),
		Map.entry("headdb", "HeadDatabase"),
		Map.entry("huskhomes", "HuskHomes"),
		Map.entry("husksync", "HuskSync"),
		Map.entry("itemedit", "ItemEdit"),
		Map.entry("itemsadder", "ItemsAdder"),
		Map.entry("jobs", "Jobs Reborn"),
		Map.entry("libertybans", "LibertyBans"),
		Map.entry("litebans", "LiteBans"),
		Map.entry("lp", "LuckPerms"),
		Map.entry("luckperms", "LuckPerms"),
		Map.entry("lwc", "LWC"),
		Map.entry("matrix", "Matrix"),
		Map.entry("mcmmo", "mcMMO"),
		Map.entry("minimotd", "MiniMOTD"),
		Map.entry("multiverse", "Multiverse"),
		Map.entry("mv", "Multiverse"),
		Map.entry("mvtp", "Multiverse"),
		Map.entry("mythicmobs", "MythicMobs"),
		Map.entry("ncp", "NoCheatPlus"),
		Map.entry("negativity", "Negativity"),
		Map.entry("nlogin", "nLogin"),
		Map.entry("nocheatplus", "NoCheatPlus"),
		Map.entry("npc", "Citizens"),
		Map.entry("openinv", "OpenInv"),
		Map.entry("oraxen", "Oraxen"),
		Map.entry("papi", "PlaceholderAPI"),
		Map.entry("pex", "PermissionsEx"),
		Map.entry("placeholderapi", "PlaceholderAPI"),
		Map.entry("playerpoints", "PlayerPoints"),
		Map.entry("plotsquared", "PlotSquared"),
		Map.entry("plugman", "PlugMan"),
		Map.entry("plugmanx", "PlugManX"),
		Map.entry("protocol", "ProtocolLib"),
		Map.entry("residence", "Residence"),
		Map.entry("serverlistplus", "ServerListPlus"),
		Map.entry("shopkeepers", "Shopkeepers"),
		Map.entry("skript", "Skript"),
		Map.entry("slimefun", "Slimefun"),
		Map.entry("spark", "spark"),
		Map.entry("spartan", "Spartan"),
		Map.entry("squaremap", "squaremap"),
		Map.entry("tab", "TAB"),
		Map.entry("tebex", "Tebex"),
		Map.entry("themis", "Themis"),
		Map.entry("totemguard", "TotemGuard"),
		Map.entry("towny", "Towny"),
		Map.entry("vault", "Vault"),
		Map.entry("venturechat", "VentureChat"),
		Map.entry("via", "ViaVersion"),
		Map.entry("viaversion", "ViaVersion"),
		Map.entry("voicechat", "Simple Voice Chat"),
		Map.entry("vulcan", "Vulcan"),
		Map.entry("we", "WorldEdit"),
		Map.entry("wg", "WorldGuard"),
		Map.entry("worldedit", "WorldEdit"),
		Map.entry("worldguard", "WorldGuard")
	);

	/** How many command names are recognised. Used by the test that guards this table. */
	public static int size() {
		return BY_COMMAND.size();
	}

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
