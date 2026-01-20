package dev.flamebeast.serverinsight.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.flamebeast.serverinsight.state.ServerInsightRuntime;
import dev.flamebeast.serverinsight.text.ChatFormat;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.command.permission.PermissionPredicate;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.net.URI;

public final class ServerInsightCommand {
	private ServerInsightCommand() {
	}

	private static final int YELLOW_RGB = 0xFFFF00;
	private static final int AQUA_RGB = 0x00FFFF;
	private static final int ORANGE_RGB = 0xFF6C00;
	private static final int POPULAR_RGB = 0x00FF00;

	private static final Set<String> POPULAR_PLUGINS = Set.of(
		"essentials", "essentialsx", "cmi", "tab", "luckperms", "vault",
		"worldedit", "worldguard", "viaversion", "viabackwards", "viarewind",
		"placeholderapi", "coreprotect", "protocolib", "lpc", "excellentcrates",
		"citizens", "dynmap", "multiverse-core", "multiverse", "skript", "gcore",
		"discordsrv", "decentholograms", "advancedban", "plugmanx", "itemedit",
		"pheonixcrateslite", "pheonixcrates", "setspawn", "serverlistplus", "minimotd", "skbee"
	);

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommandManager.literal("serverinsight")
			.executes(ctx -> showSummary(ctx.getSource()))		
		);
	}

	private static int showSummary(FabricClientCommandSource source) {
		MinecraftClient mc = source.getClient();
		ClientPlayNetworkHandler network = mc.getNetworkHandler();

		send(source, ChatFormat.header("Server Insight"));
		printClientDetails(source, mc);

		if (mc.isIntegratedServerRunning()) {
			printSingleplayer(source, mc);
			printPlayerDetails(source, mc, null);
			printWorldDetails(source);
			printTps(source);
			printPlugins(source);
			printSupport(source);
			return 1;
		}

		if (network == null) {
			send(source, ChatFormat.prefix().append(Text.literal("Not connected.").formatted(Formatting.RED)));
			printSupport(source);
			return 0;
		}

		printMultiplayer(source, mc, network);
		printPlayerDetails(source, mc, network);
		printWorldDetails(source);
		printTps(source);
		printPlugins(source);
		printSupport(source);
		return 1;
	}

	private static void printSupport(FabricClientCommandSource source) {
		String url = "https://paypal.me/theflamebeast";
		Text link = Text.literal(url)
			.styled(style -> style
				.withColor(TextColor.fromFormatting(Formatting.DARK_GRAY))
				.withUnderline(true)
				.withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
				.withHoverEvent(new HoverEvent.ShowText(Text.literal("Open support link").formatted(Formatting.GRAY)))
			);

		send(source, ChatFormat.prefix()
			.append(Text.literal("Support: ").formatted(Formatting.DARK_GRAY))
			.append(link)
		);
	}

	private static void printTps(FabricClientCommandSource source) {
		double tps = ServerInsightRuntime.INSTANCE.timing().getEstimatedTps();
		Formatting color = tps >= 19.5 ? Formatting.GREEN : tps >= 17.5 ? Formatting.YELLOW : tps >= 14.0 ? Formatting.GOLD : Formatting.RED;
		double mspt = tps <= 0.0 ? 0.0 : (1000.0 / tps);
		Text value = Text.literal(String.format("%.2f", tps)).formatted(color)
			.append(Text.literal(" TPS").formatted(Formatting.GRAY))
			.append(Text.literal("  |  ").formatted(Formatting.DARK_GRAY))
			.append(Text.literal(String.format("%.1f", mspt)).formatted(Formatting.GRAY))
			.append(Text.literal(" ms/t").formatted(Formatting.DARK_GRAY))
			.append(Text.literal(" (est)").formatted(Formatting.DARK_GRAY));
		send(source, ChatFormat.kv("Perf", value));
	}

	private static void printPlugins(FabricClientCommandSource source) {
		MinecraftClient mc = source.getClient();
		if (mc.getNetworkHandler() == null) {
			send(source, ChatFormat.kv("Plugins", Text.literal("N/A (not connected)").formatted(Formatting.DARK_GRAY)));
			return;
		}

		Consumer<Text> out = msg -> send(source, msg);

		// Always show what we already learned from the command tree immediately.
		printPluginsLine(out, false);

		CompletableFuture<List<String>> scanFuture = ServerInsightRuntime.INSTANCE.plugins().requestCompletionScan();
		if (!scanFuture.isDone()) {
			out.accept(ChatFormat.prefix().append(Text.literal("Scanning extra plugin hints (tab completion)...")
				.styled(style -> style.withColor(TextColor.fromRgb(ORANGE_RGB)))));
		}

		scanFuture.whenComplete((ignored, throwable) -> mc.execute(() -> {
			if (throwable != null) {
				out.accept(ChatFormat.prefix().append(Text.literal("Plugin scan failed: ").formatted(Formatting.RED))
					.append(Text.literal(throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage()).formatted(Formatting.DARK_RED)));
			}
			printPluginsLine(out, true);
		}));
	}

	private static void printPluginsLine(Consumer<Text> out, boolean includeList) {
		Set<String> plugins = ServerInsightRuntime.INSTANCE.plugins().combinedPlugins();
		int fromTree = ServerInsightRuntime.INSTANCE.plugins().commandTreeCount();
		int fromTab = ServerInsightRuntime.INSTANCE.plugins().completionCount();

		Text summary = plugins.isEmpty()
			? Text.literal("None detected").formatted(Formatting.YELLOW).copy()
				.append(Text.literal(" (server may hide this)").formatted(Formatting.DARK_GRAY))
			: Text.literal(String.valueOf(plugins.size())).formatted(Formatting.YELLOW).copy()
				.append(Text.literal(" detected").formatted(Formatting.GRAY))
				.append(Text.literal("  cmd:").formatted(Formatting.DARK_GRAY))
				.append(Text.literal(String.valueOf(fromTree)).formatted(Formatting.DARK_GRAY))
				.append(Text.literal(" tab:").formatted(Formatting.DARK_GRAY))
				.append(Text.literal(String.valueOf(fromTab)).formatted(Formatting.DARK_GRAY));

		MutableText summaryLine = ChatFormat.kv("Plugins", summary);
		if (!plugins.isEmpty()) {
			String csv = String.join(", ", plugins);
			summaryLine.append(Text.literal("  [copy]")
				.styled(style -> style
					.withColor(TextColor.fromRgb(AQUA_RGB))
					.withClickEvent(new ClickEvent.CopyToClipboard(csv))
					.withHoverEvent(new HoverEvent.ShowText(Text.literal("Copy plugin list").formatted(Formatting.WHITE)))
				)
			);
		}
		out.accept(summaryLine);

		if (!includeList || plugins.isEmpty()) {
			return;
		}

		List<String> sorted = new ArrayList<>(plugins);
		MutableText line = ChatFormat.prefix().append(Text.literal("• ").formatted(Formatting.DARK_GRAY));
		for (int i = 0; i < sorted.size(); i++) {
			String name = sorted.get(i);
			line.append(formatPluginName(name));
			if (i < sorted.size() - 1) {
				line.append(Text.literal(", ").formatted(Formatting.DARK_GRAY));
			}
		}
		out.accept(line);
	}

	private static void printSingleplayer(FabricClientCommandSource source, MinecraftClient mc) {
		IntegratedServer server = mc.getServer();
		send(source, ChatFormat.kv("Type", Text.literal("Singleplayer").styled(style -> style.withColor(TextColor.fromRgb(AQUA_RGB)))));
		if (server != null) {
			send(source, ChatFormat.kv("Version", Text.literal(server.getVersion()).formatted(Formatting.YELLOW)));
		}
		send(source, ChatFormat.kv("Difficulty", difficultyText(source)));
		send(source, ChatFormat.kv("Permissions", permissionText(source)));
	}

	private static void printMultiplayer(FabricClientCommandSource source, MinecraftClient mc, ClientPlayNetworkHandler network) {
		ServerInfo serverInfo = mc.getCurrentServerEntry();
		String displayAddress;
		int port;

		if (serverInfo != null) {
			displayAddress = serverInfo.address;
			ServerAddress parsed = ServerAddress.parse(displayAddress);
			port = parsed.getPort();
			send(source, ChatFormat.kv("Address", clickableAddress(displayAddress, resolve(parsed.getAddress()), port)));
			Text motd = Objects.requireNonNullElse(serverInfo.label, Text.literal("N/A").formatted(Formatting.DARK_GRAY));
			send(source, ChatFormat.kv("MOTD", motd.copy().formatted(Formatting.GRAY)));
			send(source, ChatFormat.kv("Version", serverInfo.version.copy().formatted(Formatting.YELLOW)));
			send(source, ChatFormat.kv("Protocol", Text.literal(String.valueOf(serverInfo.protocolVersion)).formatted(Formatting.YELLOW)));
		} else {
			ServerAddress parsed = ServerAddress.parse(network.getConnection().getAddress().toString());
			displayAddress = parsed.getAddress() + ":" + parsed.getPort();
			port = parsed.getPort();
			send(source, ChatFormat.kv("Address", clickableAddress(displayAddress, resolve(parsed.getAddress()), port)));
		}

		String brand = network.getBrand();
		Text brandText = Text.literal(brand == null ? "unknown" : brand)
			.styled(style -> style.withColor(TextColor.fromRgb(YELLOW_RGB)));
		send(source, ChatFormat.kv("Brand", brandText));

		int online = safeOnlineCount(network);
		if (online >= 0) {
			send(source, ChatFormat.kv("Players", Text.literal(String.valueOf(online)).formatted(Formatting.YELLOW)));
		}

		send(source, ChatFormat.kv("Difficulty", difficultyText(source)));
		send(source, ChatFormat.kv("Permissions", permissionText(source)));
	}

	private static void printClientDetails(FabricClientCommandSource source, MinecraftClient mc) {
		Text value = Text.literal("Java ").formatted(Formatting.DARK_GRAY)
			.append(Text.literal(System.getProperty("java.version", "?")).formatted(Formatting.GRAY))
			.append(Text.literal("  |  ").formatted(Formatting.DARK_GRAY))
			.append(Text.literal("FPS ").formatted(Formatting.DARK_GRAY))
			.append(Text.literal(String.valueOf(mc.getCurrentFps())).formatted(Formatting.GRAY));
		send(source, ChatFormat.kv("Client", value));
	}

	private static void printPlayerDetails(FabricClientCommandSource source, MinecraftClient mc, ClientPlayNetworkHandler network) {
		ClientPlayerEntity player = source.getPlayer();
		if (player == null) {
			return;
		}

		Text who = Text.literal(player.getName().getString()).styled(style -> style.withColor(TextColor.fromRgb(AQUA_RGB)))
			.styled(style -> style.withHoverEvent(new HoverEvent.ShowText(Text.literal(player.getUuidAsString()).formatted(Formatting.GRAY))));
		send(source, ChatFormat.kv("You", who));

		GameMode mode = (mc.interactionManager == null) ? null : mc.interactionManager.getCurrentGameMode();
		Text gmText = mode == null
			? Text.literal("unknown").styled(style -> style.withColor(TextColor.fromRgb(AQUA_RGB)))
			: mode.getTranslatableName().copy().styled(style -> style.withColor(TextColor.fromRgb(AQUA_RGB)));

		PlayerListEntry entry = (network == null) ? null : network.getPlayerListEntry(player.getUuid());
		Text pingText = entry == null
			? Text.literal("N/A").formatted(Formatting.GRAY)
			: Text.literal(entry.getLatency() + " ms")
				.formatted(entry.getLatency() <= 80 ? Formatting.GREEN : entry.getLatency() <= 150 ? Formatting.YELLOW : Formatting.RED);

		Text combined = gmText.copy()
			.append(Text.literal("  |  ").formatted(Formatting.DARK_GRAY))
			.append(Text.literal("Ping ").formatted(Formatting.GRAY))
			.append(pingText);
		send(source, ChatFormat.kv("Mode", combined));

		String coordsCopy = String.format("%.1f %.1f %.1f", player.getX(), player.getY(), player.getZ());
		Text pos = Text.literal(String.format("%.1f, %.1f, %.1f", player.getX(), player.getY(), player.getZ())).formatted(Formatting.GRAY)
			.styled(style -> style
				.withClickEvent(new ClickEvent.CopyToClipboard(coordsCopy))
				.withHoverEvent(new HoverEvent.ShowText(Text.literal("Click to copy coordinates").formatted(Formatting.WHITE))));
		send(source, ChatFormat.kv("Pos", pos));
	}

	private static void printWorldDetails(FabricClientCommandSource source) {
		if (source.getWorld() == null) {
			return;
		}

		Identifier dim = source.getWorld().getRegistryKey().getValue();
		send(source, ChatFormat.kv("Dim", Text.literal(dim.toString()).formatted(Formatting.GRAY)));

		ClientPlayerEntity player = source.getPlayer();
		if (player != null) {
			String biomeId;
			try {
				var biomeEntry = source.getWorld().getBiome(player.getBlockPos());
				biomeId = biomeEntry.getKey().map(key -> key.getValue().toString()).orElse("unknown");
			} catch (Throwable ignored) {
				biomeId = "unknown";
			}
			send(source, ChatFormat.kv("Biome", Text.literal(biomeId).formatted(biomeId.equals("unknown") ? Formatting.DARK_GRAY : Formatting.GRAY)));
		}

		long worldTime = source.getWorld().getTimeOfDay();
		long day = Math.max(0, worldTime / 24000L);
		long dayTick = Math.floorMod(worldTime, 24000L);
		Text time = Text.literal("Day " + day + "  (tick " + dayTick + ")").formatted(Formatting.YELLOW)
			.append(Text.literal("  |  ").formatted(Formatting.DARK_GRAY))
			.append(Text.literal(formatMinecraftClock(dayTick)).formatted(Formatting.GRAY));
		send(source, ChatFormat.kv("Time", time));

		boolean raining = source.getWorld().isRaining();
		boolean thundering = source.getWorld().isThundering();
		Text weather = Text.literal(raining ? (thundering ? "Thunder" : "Rain") : "Clear")
			.styled(style -> style.withColor(TextColor.fromRgb(YELLOW_RGB)));
		send(source, ChatFormat.kv("Weather", weather));
	}

	private static MutableText clickableAddress(String shown, String resolvedIp, int port) {
		String copy = shown;
		String resolvedWithPort = resolvedIp + ":" + port;
		boolean showResolved = resolvedIp != null && !resolvedIp.equalsIgnoreCase("localhost") && !resolvedIp.equalsIgnoreCase("127.0.0.1") && !resolvedIp.equals(shown.split(":")[0]);

		MutableText base = Text.literal(shown).formatted(Formatting.GRAY)
			.setStyle(Style.EMPTY
				.withClickEvent(new ClickEvent.CopyToClipboard(copy))
				.withHoverEvent(new HoverEvent.ShowText(Text.literal("Click to copy").formatted(Formatting.WHITE)))
			);

		if (!showResolved) {
			return base;
		}

		return base.append(Text.literal("  (").formatted(Formatting.DARK_GRAY))
			.append(Text.literal(resolvedWithPort).formatted(Formatting.GRAY)
				.setStyle(Style.EMPTY
					.withClickEvent(new ClickEvent.CopyToClipboard(resolvedWithPort))
					.withHoverEvent(new HoverEvent.ShowText(Text.literal("Click to copy resolved IP").formatted(Formatting.WHITE)))
				))
			.append(Text.literal(")").formatted(Formatting.DARK_GRAY));
	}

	private static String resolve(String host) {
		if (host == null) {
			return null;
		}
		if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "0.0.0.0".equals(host)) {
			return host;
		}
		try {
			return InetAddress.getByName(host).getHostAddress();
		} catch (UnknownHostException | SecurityException e) {
			return host;
		}
	}

	private static Text difficultyText(FabricClientCommandSource source) {
		Difficulty difficulty = source.getWorld().getDifficulty();
		int numeric = switch (difficulty) {
			case PEACEFUL -> 0;
			case EASY -> 1;
			case NORMAL -> 2;
			case HARD -> 3;
		};
		return difficulty.getTranslatableName().copy().formatted(Formatting.YELLOW)
			.append(Text.literal(" (" + numeric + ")").formatted(Formatting.DARK_GRAY));
	}

	private static String formatMinecraftClock(long dayTick) {
		// In vanilla: tick 0 == 06:00. Shift so 00:00 aligns at tick 18000.
		long adjusted = Math.floorMod(dayTick + 6000L, 24000L);
		int hours = (int) (adjusted / 1000L);
		int minutes = (int) ((adjusted % 1000L) * 60L / 1000L);
		return String.format("%02d:%02d", hours, minutes);
	}

	private static int safeOnlineCount(ClientPlayNetworkHandler network) {
		try {
			return network.getPlayerList().size();
		} catch (Throwable ignored) {
			return -1;
		}
	}

	private static Text permissionText(FabricClientCommandSource source) {
		int level = 0;
		ClientPlayerEntity player = source.getPlayer();
		if (player != null) {
			PermissionPredicate perm = player.getPermissions();
			if (perm instanceof LeveledPermissionPredicate leveled) {
				level = leveled.getLevel().getLevel();
			}
		}

		String label = switch (level) {
			case 0 -> "Player";
			case 1 -> "Moderator";
			case 2 -> "Game Master";
			case 3 -> "Admin";
			case 4 -> "Owner/OP";
			default -> "Level " + level;
		};
		return Text.literal(level + " (" + label + ")").formatted(Formatting.YELLOW);
	}

	private static Text formatPluginName(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		boolean security = lower.contains("anticheat")
			|| lower.contains("anti-cheat")
			|| lower.equals("ac")
			|| lower.endsWith("-ac")
			|| lower.contains("grim")
			|| lower.contains("vulcan")
			|| lower.contains("spartan")
			|| lower.contains("matrix")
			|| lower.contains("totemguard")
			|| lower.contains("themis");

		boolean popular = POPULAR_PLUGINS.contains(lower);
		TextColor color = security
			? TextColor.fromRgb(ORANGE_RGB)
			: (popular ? TextColor.fromRgb(POPULAR_RGB) : TextColor.fromRgb(YELLOW_RGB));

		MutableText hover = Text.literal("Click to copy").formatted(Formatting.WHITE);
		if (security) {
			hover.append(Text.literal("\nLikely security/anti-cheat").styled(style -> style.withColor(TextColor.fromRgb(ORANGE_RGB))));
		} else if (popular) {
			hover.append(Text.literal("\nPopular plugin").styled(style -> style.withColor(TextColor.fromRgb(POPULAR_RGB))));
		} else {
			hover.append(Text.literal("\nDetected via commands/tab").formatted(Formatting.DARK_GRAY));
		}

		return Text.literal(name)
			.styled(style -> style.withColor(color))
			.styled(style -> style
				.withClickEvent(new ClickEvent.CopyToClipboard(name))
				.withHoverEvent(new HoverEvent.ShowText(hover))
			);
	}

	private static void send(FabricClientCommandSource source, Text msg) {
		source.sendFeedback(msg);
	}
}
