package dev.flamebeast.serverinsight.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.flamebeast.serverinsight.detect.LocationInfo;
import dev.flamebeast.serverinsight.detect.ServerMods;
import dev.flamebeast.serverinsight.detect.ServerSoftware;
import dev.flamebeast.serverinsight.state.GeoLocator;
import dev.flamebeast.serverinsight.state.ServerInsightRuntime;
import dev.flamebeast.serverinsight.text.ChatFormat;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

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
		dispatcher.register(ClientCommands.literal("serverinsight")
			.executes(ctx -> showSummary(ctx.getSource()))
		);
	}

	private static int showSummary(FabricClientCommandSource source) {
		Minecraft mc = source.getClient();
		ClientPacketListener network = mc.getConnection();

		send(source, ChatFormat.header("Server Insight"));
		printClientDetails(source, mc);

		if (mc.isLocalServer()) {
			printSingleplayer(source, mc);
			printPlayerDetails(source, mc, null);
			printWorldDetails(source, mc);
			printTps(source);
			printPlugins(source, mc, null).whenComplete((ignored, throwable) -> printSupport(source));
			return 1;
		}

		if (network == null) {
			send(source, ChatFormat.prefix().append(Component.literal("Not connected.").withStyle(ChatFormatting.RED)));
			printSupport(source);
			return 0;
		}

		printMultiplayer(source, mc, network);
		printPlayerDetails(source, mc, network);
		printWorldDetails(source, mc);
		printTps(source);
		printMods(source);
		printPlugins(source, mc, network.serverBrand()).whenComplete((ignored, throwable) -> printSupport(source));
		return 1;
	}

	private static void printSupport(FabricClientCommandSource source) {
		String url = "https://paypal.me/theflamebeast";
		Component link = Component.literal(url)
			.setStyle(Style.EMPTY
				.withColor(TextColor.fromLegacyFormat(ChatFormatting.DARK_GRAY))
				.withUnderlined(true)
				.withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Open support link").withStyle(ChatFormatting.GRAY)))
			);

		send(source, ChatFormat.prefix()
			.append(Component.literal("Support: ").withStyle(ChatFormatting.DARK_GRAY))
			.append(link)
		);
	}

	private static void printTps(FabricClientCommandSource source) {
		OptionalDouble estimate = ServerInsightRuntime.INSTANCE.timing().estimatedTps();

		// No data is its own answer. Substituting a plausible 20.00 here — which this
		// used to do — is the one thing the mod must not do, since the whole point of
		// the reading is that the user can't otherwise tell.
		if (estimate.isEmpty()) {
			Component unknown = Component.literal("unknown").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(" (server sends no time updates)").withStyle(ChatFormatting.DARK_GRAY));
			send(source, ChatFormat.kv("Perf", unknown));
			return;
		}

		double tps = estimate.getAsDouble();
		ChatFormatting color = tps >= 19.5 ? ChatFormatting.GREEN : tps >= 17.5 ? ChatFormatting.YELLOW : tps >= 14.0 ? ChatFormatting.GOLD : ChatFormatting.RED;

		// ms/t used to sit here as 1000/tps, which is just the TPS restated and reads
		// like a second, independent measurement. Real ms/t is how long the server
		// spends processing a tick, which a client cannot see.
		Component value = Component.literal(String.format("%.2f", tps)).withStyle(color)
			.append(Component.literal(" TPS").withStyle(ChatFormatting.GRAY))
			.append(Component.literal(" (est)").withStyle(ChatFormatting.DARK_GRAY));
		send(source, ChatFormat.kv("Perf", value));
	}

	/**
	 * Where the address points. Uses the same cache the server list fills, so by the
	 * time anyone is in-game this is almost always already resolved; if it isn't, the
	 * lookup starts and the line is simply skipped this run rather than blocking.
	 */
	private static void printLocation(FabricClientCommandSource source, String host) {
		LocationInfo location = GeoLocator.lookup(host);
		if (location == null) {
			return;
		}

		MutableComponent value = Component.literal(location.describePlace())
			.setStyle(Style.EMPTY
				.withColor(TextColor.fromRgb(AQUA_RGB))
				.withHoverEvent(new HoverEvent.ShowText(locationHover(location)))
			);

		if (location.isp() != null) {
			value.append(Component.literal("  |  ").withStyle(ChatFormatting.DARK_GRAY))
				.append(Component.literal(location.isp()).withStyle(ChatFormatting.GRAY));
		}

		send(source, ChatFormat.kv("Location", value));
	}

	private static Component locationHover(LocationInfo location) {
		MutableComponent hover = Component.literal(location.describePlace()).withStyle(ChatFormatting.WHITE);

		appendHoverLine(hover, "IP", location.queriedIp());
		appendHoverLine(hover, "ISP", location.isp());

		if (location.org() != null && !location.org().equals(location.isp())) {
			appendHoverLine(hover, "Org", location.org());
		}

		appendHoverLine(hover, "AS", location.asName());
		appendHoverLine(hover, "Timezone", location.timezone());

		return hover.append(Component.literal("\nWhere the address points, not necessarily the host")
			.withStyle(ChatFormatting.DARK_GRAY));
	}

	private static void appendHoverLine(MutableComponent hover, String label, String value) {
		if (value == null) {
			return;
		}

		hover.append(Component.literal("\n" + label + ": ").withStyle(ChatFormatting.GRAY))
			.append(Component.literal(value).withStyle(ChatFormatting.WHITE));
	}

	private static void printSoftware(FabricClientCommandSource source, String brand) {
		ServerSoftware software = ServerSoftware.parse(brand);

		MutableComponent value = Component.literal(software.displayName())
			.setStyle(Style.EMPTY
				.withColor(TextColor.fromRgb(YELLOW_RGB))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Raw brand: " + (brand == null ? "unknown" : brand))
					.withStyle(ChatFormatting.GRAY)))
			);

		if (software.family().hint() != null) {
			value.append(Component.literal("  (" + software.family().hint() + ")").withStyle(ChatFormatting.DARK_GRAY));
		}

		if (software.proxy() != null && software.family() != ServerSoftware.Family.PROXY) {
			value.append(Component.literal("  via ").withStyle(ChatFormatting.DARK_GRAY))
				.append(Component.literal(software.proxy()).withStyle(ChatFormatting.GRAY));
		}

		send(source, ChatFormat.kv("Software", value));
	}

	/**
	 * Server-side mods, read from the channels the server declared it can receive.
	 * Only printed when something is there — on a plain Bukkit or vanilla server the
	 * line would always be empty and just add noise.
	 */
	private static void printMods(FabricClientCommandSource source) {
		Set<String> mods = ServerMods.detected();
		if (mods.isEmpty()) {
			return;
		}

		MutableComponent summary = ChatFormat.kv("Mods", Component.literal(String.valueOf(mods.size()))
			.withStyle(ChatFormatting.YELLOW)
			.append(Component.literal(" declared").withStyle(ChatFormatting.GRAY)));

		summary.append(Component.literal("  [copy]")
			.setStyle(Style.EMPTY
				.withColor(TextColor.fromRgb(AQUA_RGB))
				.withClickEvent(new ClickEvent.CopyToClipboard(String.join(", ", mods)))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Copy mod list").withStyle(ChatFormatting.WHITE)))
			));
		send(source, summary);

		MutableComponent line = ChatFormat.prefix().append(Component.literal("• ").withStyle(ChatFormatting.DARK_GRAY));
		int i = 0;
		for (String mod : mods) {
			line.append(Component.literal(mod)
				.setStyle(Style.EMPTY
					.withColor(TextColor.fromRgb(YELLOW_RGB))
					.withClickEvent(new ClickEvent.CopyToClipboard(mod))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy").withStyle(ChatFormatting.WHITE)
						.append(Component.literal("\nFrom declared network channels — mods without networking are invisible")
							.withStyle(ChatFormatting.DARK_GRAY))))
				));
			if (++i < mods.size()) {
				line.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
			}
		}
		send(source, line);
	}

	private static CompletableFuture<Void> printPlugins(FabricClientCommandSource source, Minecraft mc, String brand) {
		if (mc.getConnection() == null) {
			send(source, ChatFormat.kv("Plugins", Component.literal("N/A (not connected)").withStyle(ChatFormatting.DARK_GRAY)));
			return CompletableFuture.completedFuture(null);
		}

		Consumer<Component> out = msg -> send(source, msg);
		ServerSoftware.Family family = ServerSoftware.parse(brand).family();

		printPluginsLine(out, false, family);

		CompletableFuture<List<String>> scanFuture = ServerInsightRuntime.INSTANCE.plugins().requestCompletionScan();
		CompletableFuture<Void> done = new CompletableFuture<>();
		if (!scanFuture.isDone()) {
			out.accept(ChatFormat.prefix().append(Component.literal("Scanning extra plugin hints (tab completion)...")
				.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(ORANGE_RGB)))));
		}

		scanFuture.whenComplete((ignored, throwable) -> mc.execute(() -> {
			if (throwable != null) {
				out.accept(ChatFormat.prefix().append(Component.literal("Plugin scan failed: ").withStyle(ChatFormatting.RED))
					.append(Component.literal(throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage()).withStyle(ChatFormatting.DARK_RED)));
			}
			printPluginsLine(out, true, family);
			done.complete(null);
		}));

		return done;
	}

	private static void printPluginsLine(Consumer<Component> out, boolean includeList, ServerSoftware.Family family) {
		Set<String> plugins = ServerInsightRuntime.INSTANCE.plugins().combinedPlugins();
		int fromTree = ServerInsightRuntime.INSTANCE.plugins().commandTreeCount();
		int fromTab = ServerInsightRuntime.INSTANCE.plugins().completionCount();
		int guessed = ServerInsightRuntime.INSTANCE.plugins().guessCount();

		// The per-source breakdown is the user's only handle on how much to trust the
		// total, so guesses get their own counter rather than being folded in silently.
		Component summary = plugins.isEmpty()
			? Component.literal("None detected").withStyle(ChatFormatting.YELLOW)
				.append(Component.literal(" (" + emptyPluginsReason(family) + ")").withStyle(ChatFormatting.DARK_GRAY))
			: Component.literal(String.valueOf(plugins.size())).withStyle(ChatFormatting.YELLOW)
				.append(Component.literal(" detected").withStyle(ChatFormatting.GRAY))
				.append(Component.literal("  cmd:").withStyle(ChatFormatting.DARK_GRAY))
				.append(Component.literal(String.valueOf(fromTree)).withStyle(ChatFormatting.DARK_GRAY))
				.append(Component.literal(" tab:").withStyle(ChatFormatting.DARK_GRAY))
				.append(Component.literal(String.valueOf(fromTab)).withStyle(ChatFormatting.DARK_GRAY))
				.append(Component.literal(" guess:").withStyle(ChatFormatting.DARK_GRAY))
				.append(Component.literal(String.valueOf(guessed)).withStyle(ChatFormatting.DARK_GRAY));

		MutableComponent summaryLine = ChatFormat.kv("Plugins", summary);
		if (!plugins.isEmpty()) {
			String csv = String.join(", ", plugins);
			summaryLine.append(Component.literal("  [copy]")
				.setStyle(Style.EMPTY
					.withColor(TextColor.fromRgb(AQUA_RGB))
					.withClickEvent(new ClickEvent.CopyToClipboard(csv))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Copy plugin list").withStyle(ChatFormatting.WHITE)))
				)
			);
		}
		out.accept(summaryLine);

		if (!includeList || plugins.isEmpty()) {
			return;
		}

		List<String> sorted = new ArrayList<>(plugins);
		Set<String> guesses = ServerInsightRuntime.INSTANCE.plugins().guessedPlugins();
		MutableComponent line = ChatFormat.prefix().append(Component.literal("• ").withStyle(ChatFormatting.DARK_GRAY));
		for (int i = 0; i < sorted.size(); i++) {
			String name = sorted.get(i);
			line.append(formatPluginName(name, guesses.contains(name)));
			if (i < sorted.size() - 1) {
				line.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
			}
		}
		out.accept(line);
	}

	/**
	 * "None detected" means something different depending on what the server runs. On
	 * vanilla or a modded server it is the correct and complete answer; only on a
	 * Bukkit-family server does it actually suggest the server is withholding.
	 */
	private static String emptyPluginsReason(ServerSoftware.Family family) {
		return switch (family) {
			case VANILLA -> "vanilla server has no plugin system";
			case MODDED -> "modded server, mods are listed above";
			case BUKKIT -> "server may hide this";
			default -> "server may hide this";
		};
	}

	private static void printSingleplayer(FabricClientCommandSource source, Minecraft mc) {
		IntegratedServer server = mc.getSingleplayerServer();
		send(source, ChatFormat.kv("Type", Component.literal("Singleplayer").setStyle(Style.EMPTY.withColor(TextColor.fromRgb(AQUA_RGB)))));
		if (server != null) {
			send(source, ChatFormat.kv("Version", Component.literal(server.getServerVersion()).withStyle(ChatFormatting.YELLOW)));
		}
		send(source, ChatFormat.kv("Difficulty", difficultyText(mc)));
		send(source, ChatFormat.kv("Permissions", permissionText(source)));
	}

	private static void printMultiplayer(FabricClientCommandSource source, Minecraft mc, ClientPacketListener network) {
		ServerData serverInfo = mc.getCurrentServer();
		String displayAddress;
		String host;
		int port;

		if (serverInfo != null) {
			displayAddress = serverInfo.ip;
			ServerAddress parsed = ServerAddress.parseString(displayAddress);
			host = parsed.getHost();
			port = parsed.getPort();
			send(source, ChatFormat.kv("Address", clickableAddress(displayAddress, resolvedIp(parsed.getHost()), port)));
			Component motd = Objects.requireNonNullElse(serverInfo.motd, Component.literal("N/A").withStyle(ChatFormatting.DARK_GRAY));
			send(source, ChatFormat.kv("MOTD", motd.copy().withStyle(ChatFormatting.GRAY)));
			send(source, ChatFormat.kv("Version", serverInfo.version.copy().withStyle(ChatFormatting.YELLOW)));
			send(source, ChatFormat.kv("Protocol", Component.literal(String.valueOf(serverInfo.protocol)).withStyle(ChatFormatting.YELLOW)));
		} else {
			ServerAddress parsed = ServerAddress.parseString(network.getConnection().getRemoteAddress().toString());
			displayAddress = parsed.getHost() + ":" + parsed.getPort();
			host = parsed.getHost();
			port = parsed.getPort();
			send(source, ChatFormat.kv("Address", clickableAddress(displayAddress, resolvedIp(parsed.getHost()), port)));
		}

		printLocation(source, host);
		printSoftware(source, network.serverBrand());

		int online = safeOnlineCount(network);
		if (online >= 0) {
			send(source, ChatFormat.kv("Players", Component.literal(String.valueOf(online)).withStyle(ChatFormatting.YELLOW)));
		}

		send(source, ChatFormat.kv("Difficulty", difficultyText(mc)));
		send(source, ChatFormat.kv("Permissions", permissionText(source)));
	}

	private static void printClientDetails(FabricClientCommandSource source, Minecraft mc) {
		Component value = Component.literal("Java ").withStyle(ChatFormatting.DARK_GRAY)
			.append(Component.literal(System.getProperty("java.version", "?")).withStyle(ChatFormatting.GRAY))
			.append(Component.literal("  |  ").withStyle(ChatFormatting.DARK_GRAY))
			.append(Component.literal("FPS ").withStyle(ChatFormatting.DARK_GRAY))
			.append(Component.literal(String.valueOf(mc.getFps())).withStyle(ChatFormatting.GRAY));
		send(source, ChatFormat.kv("Client", value));
	}

	private static void printPlayerDetails(FabricClientCommandSource source, Minecraft mc, ClientPacketListener network) {
		LocalPlayer player = source.getPlayer();
		if (player == null) {
			return;
		}

		Component who = Component.literal(player.getName().getString())
			.setStyle(Style.EMPTY
				.withColor(TextColor.fromRgb(AQUA_RGB))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(player.getStringUUID()).withStyle(ChatFormatting.GRAY)))
			);
		send(source, ChatFormat.kv("You", who));

		GameType mode = (mc.gameMode == null) ? null : mc.gameMode.getPlayerMode();
		Component gmText = mode == null
			? Component.literal("unknown").setStyle(Style.EMPTY.withColor(TextColor.fromRgb(AQUA_RGB)))
			: Component.literal(mode.getName()).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(AQUA_RGB)));

		PlayerInfo entry = (network == null) ? null : network.getPlayerInfo(player.getUUID());
		Component pingText = entry == null
			? Component.literal("N/A").withStyle(ChatFormatting.GRAY)
			: Component.literal(entry.getLatency() + " ms")
				.withStyle(entry.getLatency() <= 80 ? ChatFormatting.GREEN : entry.getLatency() <= 150 ? ChatFormatting.YELLOW : ChatFormatting.RED);

		Component combined = gmText.copy()
			.append(Component.literal("  |  ").withStyle(ChatFormatting.DARK_GRAY))
			.append(Component.literal("Ping ").withStyle(ChatFormatting.GRAY))
			.append(pingText);
		send(source, ChatFormat.kv("Mode", combined));

		String coordsCopy = String.format("%.1f %.1f %.1f", player.getX(), player.getY(), player.getZ());
		Component pos = Component.literal(String.format("%.1f, %.1f, %.1f", player.getX(), player.getY(), player.getZ()))
			.setStyle(Style.EMPTY
				.withColor(TextColor.fromLegacyFormat(ChatFormatting.GRAY))
				.withClickEvent(new ClickEvent.CopyToClipboard(coordsCopy))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy coordinates").withStyle(ChatFormatting.WHITE)))
			);
		send(source, ChatFormat.kv("Pos", pos));
	}

	private static void printWorldDetails(FabricClientCommandSource source, Minecraft mc) {
		var world = mc.level;
		if (world == null) {
			return;
		}

		String dim = world.dimension().identifier().toString();
		send(source, ChatFormat.kv("Dim", Component.literal(dim).withStyle(ChatFormatting.GRAY)));

		LocalPlayer player = source.getPlayer();
		if (player != null) {
			String biomeId;
			try {
				var biomeEntry = world.getBiome(player.blockPosition());
				biomeId = biomeEntry.unwrapKey().map(key -> key.identifier().toString()).orElse("unknown");
			} catch (Throwable ignored) {
				biomeId = "unknown";
			}
			send(source, ChatFormat.kv("Biome", Component.literal(biomeId).withStyle(biomeId.equals("unknown") ? ChatFormatting.DARK_GRAY : ChatFormatting.GRAY)));
		}

		long worldTime = world.getDefaultClockTime();
		long day = Math.max(0, worldTime / 24000L);
		long dayTick = Math.floorMod(worldTime, 24000L);
		Component time = Component.literal("Day " + day + "  (tick " + dayTick + ")").withStyle(ChatFormatting.YELLOW)
			.append(Component.literal("  |  ").withStyle(ChatFormatting.DARK_GRAY))
			.append(Component.literal(formatMinecraftClock(dayTick)).withStyle(ChatFormatting.GRAY));
		send(source, ChatFormat.kv("Time", time));

		boolean raining = world.isRaining();
		boolean thundering = world.isThundering();
		Component weather = Component.literal(raining ? (thundering ? "Thunder" : "Rain") : "Clear")
			.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(YELLOW_RGB)));
		send(source, ChatFormat.kv("Weather", weather));
	}

	private static MutableComponent clickableAddress(String shown, String resolvedIp, int port) {
		String copy = shown;
		String resolvedWithPort = resolvedIp + ":" + port;
		boolean showResolved = resolvedIp != null && !resolvedIp.equalsIgnoreCase("localhost") && !resolvedIp.equalsIgnoreCase("127.0.0.1") && !resolvedIp.equals(shown.split(":")[0]);

		MutableComponent base = Component.literal(shown)
			.setStyle(Style.EMPTY
				.withColor(TextColor.fromLegacyFormat(ChatFormatting.GRAY))
				.withClickEvent(new ClickEvent.CopyToClipboard(copy))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy").withStyle(ChatFormatting.WHITE)))
			);

		if (!showResolved) {
			return base;
		}

		return base.append(Component.literal("  (").withStyle(ChatFormatting.DARK_GRAY))
			.append(Component.literal(resolvedWithPort)
				.setStyle(Style.EMPTY
					.withColor(TextColor.fromLegacyFormat(ChatFormatting.GRAY))
					.withClickEvent(new ClickEvent.CopyToClipboard(resolvedWithPort))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy resolved IP").withStyle(ChatFormatting.WHITE)))
				))
			.append(Component.literal(")").withStyle(ChatFormatting.DARK_GRAY));
	}

	/**
	 * Whatever the join-time lookup produced. Null while it is still in flight or if it
	 * failed, in which case the address line just omits the resolved IP — never block
	 * the client thread here waiting for DNS.
	 */
	private static String resolvedIp(String host) {
		if (host == null || "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "0.0.0.0".equals(host)) {
			return host;
		}

		return ServerInsightRuntime.INSTANCE.address().resolvedFor(host);
	}

	private static Component difficultyText(Minecraft mc) {
		var world = mc.level;
		if (world == null) {
			return Component.literal("unknown").withStyle(ChatFormatting.DARK_GRAY);
		}
		Difficulty difficulty = world.getDifficulty();
		int numeric = switch (difficulty) {
			case PEACEFUL -> 0;
			case EASY -> 1;
			case NORMAL -> 2;
			case HARD -> 3;
		};
		return difficulty.getDisplayName().copy().withStyle(ChatFormatting.YELLOW)
			.append(Component.literal(" (" + numeric + ")").withStyle(ChatFormatting.DARK_GRAY));
	}

	private static String formatMinecraftClock(long dayTick) {
		long adjusted = Math.floorMod(dayTick + 6000L, 24000L);
		int hours = (int) (adjusted / 1000L);
		int minutes = (int) ((adjusted % 1000L) * 60L / 1000L);
		return String.format("%02d:%02d", hours, minutes);
	}

	private static int safeOnlineCount(ClientPacketListener network) {
		try {
			return network.getOnlinePlayers().size();
		} catch (Throwable ignored) {
			return -1;
		}
	}

	private static Component permissionText(FabricClientCommandSource source) {
		int level = 0;
		PermissionSet permissions = source.permissions();
		if (permissions.hasPermission(Permissions.COMMANDS_OWNER)) {
			level = 4;
		} else if (permissions.hasPermission(Permissions.COMMANDS_ADMIN)) {
			level = 3;
		} else if (permissions.hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
			level = 2;
		} else if (permissions.hasPermission(Permissions.COMMANDS_MODERATOR)) {
			level = 1;
		}

		String label = switch (level) {
			case 0 -> "Player";
			case 1 -> "Moderator";
			case 2 -> "Game Master";
			case 3 -> "Admin";
			case 4 -> "Owner/OP";
			default -> "Level " + level;
		};
		return Component.literal(level + " (" + label + ")").withStyle(ChatFormatting.YELLOW);
	}

	private static Component formatPluginName(String name, boolean guessed) {
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

		// Guesses are greyed out rather than coloured by category — the point of the
		// colour is confidence, and an inferred plugin has none to convey.
		TextColor color = guessed
			? TextColor.fromLegacyFormat(ChatFormatting.GRAY)
			: security
				? TextColor.fromRgb(ORANGE_RGB)
				: (popular ? TextColor.fromRgb(POPULAR_RGB) : TextColor.fromRgb(YELLOW_RGB));

		MutableComponent hover = Component.literal("Click to copy").withStyle(ChatFormatting.WHITE);
		if (guessed) {
			hover.append(Component.literal("\nGUESS — inferred from a command name, not confirmed")
				.withStyle(ChatFormatting.GRAY));
		} else if (security) {
			hover.append(Component.literal("\nLikely security/anti-cheat").setStyle(Style.EMPTY.withColor(TextColor.fromRgb(ORANGE_RGB))));
		} else if (popular) {
			hover.append(Component.literal("\nPopular plugin").setStyle(Style.EMPTY.withColor(TextColor.fromRgb(POPULAR_RGB))));
		} else {
			hover.append(Component.literal("\nDetected via commands/tab").withStyle(ChatFormatting.DARK_GRAY));
		}

		MutableComponent label = Component.literal(name);
		if (guessed) {
			label.append(Component.literal("?").withStyle(ChatFormatting.DARK_GRAY));
		}

		return label.setStyle(Style.EMPTY
			.withColor(color)
			.withClickEvent(new ClickEvent.CopyToClipboard(name))
			.withHoverEvent(new HoverEvent.ShowText(hover))
		);
	}

	private static void send(FabricClientCommandSource source, Component msg) {
		source.sendFeedback(msg);
	}
}
