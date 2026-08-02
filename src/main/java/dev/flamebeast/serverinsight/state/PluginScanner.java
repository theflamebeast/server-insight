package dev.flamebeast.serverinsight.state;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import dev.flamebeast.serverinsight.detect.CommandFingerprints;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Detects server plugins three ways, in descending order of confidence.
 *
 * 1. Namespaced root commands ("essentials:home" -> essentials). Passive.
 * 2. Tab-completion probes against commands that list plugins ("/version <TAB>").
 *    The only thing in this mod that sends anything, and only when the user asks.
 * 3. Command-name fingerprints ("/lp" -> LuckPerms). Passive, and a GUESS — kept in
 *    its own bucket so the output can present it at lower confidence.
 *
 * All of it is bounded and one-shot per command invocation: no background polling, and
 * the probes only target aliases the server actually advertised in its command tree.
 */
public final class PluginScanner {
	/** Commands that list plugins when tab-completed. Probed only if the server has them. */
	private static final Set<String> PROBE_ALIASES = Set.of(
		"version", "ver", "about", "plugins", "pl", "icanhasbukkit",
		"bukkit:version", "bukkit:ver", "bukkit:about", "bukkit:plugins", "bukkit:pl",
		"paper:version", "paper:plugins", "purpur:version"
	);

	/** Keeps one command from turning into a dozen packets on a server with many aliases. */
	private static final int MAX_PROBES = 6;

	private static final int TIMEOUT_TICKS = 100;

	/** Rejects suggestion text that cannot plausibly be a plugin name. */
	private static final Pattern PLUGIN_NAME = Pattern.compile("[A-Za-z0-9_.\\-]{2,40}");

	private final Set<String> fromCommandTree = Collections.synchronizedSet(new HashSet<>());
	private final Set<String> fromCompletionScan = Collections.synchronizedSet(new HashSet<>());
	private final Set<String> fromFingerprint = Collections.synchronizedSet(new LinkedHashSet<>());

	/** Probe aliases the server advertised, in the order they appeared. */
	private final List<String> probeAliases = Collections.synchronizedList(new ArrayList<>());

	private final Random random = new Random();

	/** In-flight probes, transaction id -> the alias it was sent for. */
	private final Map<Integer, String> pendingProbes = Collections.synchronizedMap(new HashMap<>());

	private int timeoutTicks = 0;
	private CompletableFuture<List<String>> pendingFuture = null;

	public void reset() {
		fromCommandTree.clear();
		fromCompletionScan.clear();
		fromFingerprint.clear();
		probeAliases.clear();
		pendingProbes.clear();
		timeoutTicks = 0;

		if (pendingFuture != null && !pendingFuture.isDone()) {
			pendingFuture.complete(List.of());
		}

		pendingFuture = null;
	}

	public void tick() {
		if (pendingFuture == null || pendingFuture.isDone()) {
			return;
		}

		timeoutTicks--;
		if (timeoutTicks <= 0) {
			// Whatever answered in time still counts; a server that ignores one probe
			// must not cost the user the results of the others.
			finishScan();
		}
	}

	public void onCommandTree(CommandDispatcher<?> dispatcher) {
		fromCommandTree.clear();
		fromFingerprint.clear();
		probeAliases.clear();

		if (dispatcher == null) {
			return;
		}

		CommandNode<?> root = dispatcher.getRoot();
		if (root == null) {
			return;
		}

		for (CommandNode<?> node : root.getChildren()) {
			String name = node.getName();
			String lower = name.toLowerCase(Locale.ROOT);

			if (PROBE_ALIASES.contains(lower) && probeAliases.size() < MAX_PROBES) {
				probeAliases.add(name);
			}

			String fingerprinted = CommandFingerprints.pluginFor(name);
			if (fingerprinted != null) {
				fromFingerprint.add(fingerprinted);
			}

			String[] parts = name.split(":", 2);
			if (parts.length == 2) {
				String namespace = parts[0].toLowerCase(Locale.ROOT);
				if (!namespace.equals("minecraft") && !namespace.equals("fabric")) {
					fromCommandTree.add(namespace);
				}
			}
		}
	}

	public void onCommandSuggestions(ClientboundCommandSuggestionsPacket packet) {
		if (packet == null || pendingFuture == null || pendingFuture.isDone()) {
			return;
		}

		if (pendingProbes.remove(packet.id()) == null) {
			return;
		}

		packet.suggestions().forEach(suggestion -> {
			String text = suggestion.text();
			if (!isPlausiblePluginName(text)) {
				return;
			}

			fromCompletionScan.add(text.toLowerCase(Locale.ROOT));
		});

		// Only done once every probe has answered — otherwise a fast /pl reply would
		// cut off the slower /version reply that carries the actual plugin list.
		if (pendingProbes.isEmpty()) {
			finishScan();
		}
	}

	public CompletableFuture<List<String>> requestCompletionScan() {
		Minecraft client = Minecraft.getInstance();
		ClientPacketListener network = client.getConnection();

		if (network == null) {
			return CompletableFuture.completedFuture(List.of());
		}

		if (pendingFuture != null && !pendingFuture.isDone()) {
			return CompletableFuture.failedFuture(new IllegalStateException("Plugin scan already running"));
		}

		List<String> aliases = List.copyOf(probeAliases);
		if (aliases.isEmpty()) {
			return CompletableFuture.completedFuture(List.of());
		}

		fromCompletionScan.clear();
		pendingProbes.clear();
		timeoutTicks = TIMEOUT_TICKS;
		pendingFuture = new CompletableFuture<>();

		for (String alias : aliases) {
			int transactionId = random.nextInt(Integer.MAX_VALUE - 1) + 1;
			pendingProbes.put(transactionId, alias);
			network.send(new ServerboundCommandSuggestionPacket(transactionId, alias + " "));
		}

		return pendingFuture;
	}

	private void finishScan() {
		pendingProbes.clear();
		timeoutTicks = 0;

		CompletableFuture<List<String>> future = pendingFuture;
		pendingFuture = null;

		if (future != null && !future.isDone()) {
			future.complete(List.copyOf(fromCompletionScan));
		}
	}

	/**
	 * Multiple probes mean more chances to scrape something that is not a plugin —
	 * page numbers from /help, a stray player name. Cheap shape check, not a guarantee.
	 */
	private static boolean isPlausiblePluginName(String text) {
		if (text == null || !PLUGIN_NAME.matcher(text).matches()) {
			return false;
		}

		return !text.chars().allMatch(Character::isDigit);
	}

	/** Everything detected, at any confidence. */
	public Set<String> combinedPlugins() {
		Set<String> combined = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		combined.addAll(fromCommandTree);
		combined.addAll(fromCompletionScan);
		combined.addAll(fromFingerprint);
		return combined;
	}

	/** Inferred from a command name rather than observed directly — present these as guesses. */
	public Set<String> guessedPlugins() {
		Set<String> confirmed = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		confirmed.addAll(fromCommandTree);
		confirmed.addAll(fromCompletionScan);

		Set<String> guesses = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		for (String guess : fromFingerprint) {
			if (!confirmed.contains(guess)) {
				guesses.add(guess);
			}
		}

		return guesses;
	}

	public int commandTreeCount() {
		return fromCommandTree.size();
	}

	public int completionCount() {
		return fromCompletionScan.size();
	}

	public int guessCount() {
		return guessedPlugins().size();
	}

	/** How many probes this server's command tree makes available. */
	public int probeCount() {
		return probeAliases.size();
	}
}
