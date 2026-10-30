package dev.flamebeast.serverinsight.state;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import dev.flamebeast.serverinsight.detect.CommandFingerprints;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 * Client thread only: the command, the tick hook and the packet handlers (injected at
 * TAIL, past the netty-thread deferral) all run there, so nothing here is synchronized.
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

	/**
	 * Per probe, not per scan. A reply normally lands within a tick or two; this only
	 * matters for a server that drops a probe, which then costs the user this long
	 * before the next alias is tried.
	 */
	private static final int PROBE_TIMEOUT_TICKS = 30;

	/** Rejects suggestion text that cannot plausibly be a plugin name. */
	private static final Pattern PLUGIN_NAME = Pattern.compile("[A-Za-z0-9_.\\-]{2,40}");

	private final Set<String> fromCommandTree = new HashSet<>();
	private final Set<String> fromCompletionScan = new HashSet<>();
	private final Set<String> fromFingerprint = new LinkedHashSet<>();

	/** Probe aliases the server advertised, in the order they appeared. */
	private final List<String> probeAliases = new ArrayList<>();

	private final Random random = new Random();

	/** Aliases still to be probed in the running scan. */
	private final ArrayDeque<String> probeQueue = new ArrayDeque<>();

	/** Transaction id of the one probe in flight, or 0 when none is. */
	private int inFlightId = 0;
	private int timeoutTicks = 0;
	private CompletableFuture<List<String>> pendingFuture = null;

	public void reset() {
		fromCommandTree.clear();
		fromCompletionScan.clear();
		fromFingerprint.clear();
		probeAliases.clear();
		finishScan();
	}

	public void tick() {
		if (inFlightId == 0) {
			return;
		}

		if (--timeoutTicks <= 0) {
			// A probe the server never answered costs only itself; move on rather than
			// losing the aliases still queued behind it.
			sendNextProbe();
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
		if (packet == null || inFlightId == 0 || packet.id() != inFlightId) {
			return;
		}

		packet.suggestions().forEach(suggestion -> {
			String text = suggestion.text();
			if (isPlausiblePluginName(text)) {
				fromCompletionScan.add(text.toLowerCase(Locale.ROOT));
			}
		});

		sendNextProbe();
	}

	public CompletableFuture<List<String>> requestCompletionScan() {
		if (pendingFuture != null) {
			return CompletableFuture.failedFuture(new IllegalStateException("Plugin scan already running"));
		}

		if (Minecraft.getInstance().getConnection() == null || probeAliases.isEmpty()) {
			return CompletableFuture.completedFuture(List.of());
		}

		fromCompletionScan.clear();
		probeQueue.addAll(probeAliases);
		pendingFuture = new CompletableFuture<>();

		CompletableFuture<List<String>> scan = pendingFuture;
		sendNextProbe();
		return scan;
	}

	/**
	 * One probe at a time. Since 26.3 the server keeps a single pending-suggestion slot
	 * per player and overwrites it (ServerCommandSuggestionsProvider.request does a
	 * getAndSet), because for chat typing only the latest keystroke matters. Probes sent
	 * in the same tick therefore lost all but the last — and the one dropped was
	 * /version, the probe that actually carries the plugin list.
	 */
	private void sendNextProbe() {
		ClientPacketListener network = Minecraft.getInstance().getConnection();
		String alias = probeQueue.pollFirst();

		if (network == null || alias == null) {
			finishScan();
			return;
		}

		inFlightId = random.nextInt(Integer.MAX_VALUE - 1) + 1;
		timeoutTicks = PROBE_TIMEOUT_TICKS;
		network.send(new ServerboundCommandSuggestionPacket(inFlightId, alias + " "));
	}

	private void finishScan() {
		probeQueue.clear();
		inFlightId = 0;
		timeoutTicks = 0;

		CompletableFuture<List<String>> future = pendingFuture;
		pendingFuture = null;

		if (future != null) {
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
}
