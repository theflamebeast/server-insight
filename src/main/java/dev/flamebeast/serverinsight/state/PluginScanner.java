package dev.flamebeast.serverinsight.state;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

public final class PluginScanner {
	private static final Set<String> VERSION_ALIASES = Set.of(
		"version", "ver", "about",
		"bukkit:version", "bukkit:ver", "bukkit:about"
	);

	private final Set<String> fromCommandTree = Collections.synchronizedSet(new HashSet<>());
	private final Set<String> fromCompletionScan = Collections.synchronizedSet(new HashSet<>());

	private final Random random = new Random();
	private String versionAlias = null;

	private int pendingTransactionId = -1;
	private int timeoutTicks = 0;
	private static final int TIMEOUT_TICKS = 100;
	private CompletableFuture<List<String>> pendingFuture = null;

	public void reset() {
		fromCommandTree.clear();
		fromCompletionScan.clear();
		versionAlias = null;
		pendingTransactionId = -1;
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
			pendingTransactionId = -1;
			pendingFuture.complete(List.of());
			pendingFuture = null;
		}
	}

	public void onCommandTree(CommandDispatcher<CommandSource> dispatcher) {
		fromCommandTree.clear();
		versionAlias = null;

		if (dispatcher == null) {
			return;
		}

		CommandNode<CommandSource> root = dispatcher.getRoot();
		if (root == null) {
			return;
		}

		for (CommandNode<CommandSource> node : root.getChildren()) {
			String name = node.getName();
			String lower = name.toLowerCase(Locale.ROOT);

			if (versionAlias == null && VERSION_ALIASES.contains(lower)) {
				versionAlias = name;
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

		List<String> discovered = new ArrayList<>();
		packet.suggestions().getList().forEach(suggestion -> {
			String plugin = suggestion.getText().toLowerCase(Locale.ROOT);
			if (!fromCommandTree.contains(plugin) && fromCompletionScan.add(plugin)) {
				discovered.add(plugin);
			}
		});

		pendingTransactionId = -1;
		timeoutTicks = 0;
		pendingFuture.complete(discovered);
		pendingFuture = null;
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

		if (versionAlias == null) {
			return CompletableFuture.completedFuture(List.of());
		}

		pendingTransactionId = random.nextInt(Integer.MAX_VALUE - 1) + 1;
		timeoutTicks = TIMEOUT_TICKS;
		fromCompletionScan.clear();
		pendingFuture = new CompletableFuture<>();

		network.send(new ServerboundCommandSuggestionPacket(pendingTransactionId, versionAlias + " "));
		return pendingFuture;
	}

	public Set<String> combinedPlugins() {
		Set<String> combined = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		combined.addAll(fromCommandTree);
		combined.addAll(fromCompletionScan);
		return combined;
	}

	public int commandTreeCount() {
		return fromCommandTree.size();
	}

	public int completionCount() {
		return fromCompletionScan.size();
	}
}