package dev.flamebeast.serverinsight.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.flamebeast.serverinsight.state.ServerInsightRuntime;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;

import java.util.Set;

/**
 * End-to-end check that Server Insight still works against a real multiplayer server.
 *
 * This exists because `./gradlew build` cannot catch this mod's main failure mode.
 * Mixins are matched by method name at runtime, so a Mojang rename of handleSetTime,
 * handleCommands or handleCommandSuggestions leaves the build green and breaks the mod
 * on launch. The mixin config uses defaultRequire 1, so a missing target throws while
 * ClientPacketListener is being loaded — connecting to the server below is therefore
 * enough to catch it, before a single assertion runs.
 *
 * The assertions cover the failure the crash does NOT catch: a target that still exists
 * but is no longer called on the path we assumed.
 *
 * A dedicated server rather than singleplayer, because nearly everything this mod
 * reports (brand, protocol, plugins, ping, TPS) is absent or meaningless in singleplayer.
 */
public final class ServerInsightGameTest implements FabricClientGameTest {
	/**
	 * Time-update packets arrive every 20 ticks and the completion scan allows itself
	 * 100, so the 200-tick default is uncomfortably tight on a loaded CI runner.
	 */
	private static final int SLOW_TIMEOUT = 600;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestDedicatedServerContext server = context.worldBuilder().createServer();
			TestDedicatedServerConnection connection = server.connect()) {
			connection.waitForChunksRender();

			// Minecraft 26.2 ships its own op-only /version, so registering a literal by
			// that name MERGES into it and inherits the op requirement — the whole node
			// is then filtered out of the command tree sent to a normal player, and the
			// scan silently no-ops because it never finds a version alias to probe.
			// Opping is what makes the tab-completion path reachable at all here.
			server.runCommand("op " + context.computeOnClient(mc -> mc.getUser().getName()));
			context.waitFor(mc -> mc.getConnection().getCommands().getRoot().getChild("version") != null, SLOW_TIMEOUT);

			assertCommandRegistered(context);
			assertCommandTreeMixinFired(context);
			assertTimeMixinFired(context);
			assertCommandRunsAndCompletesScan(context);

			// Not compared against a reference — it is uploaded as a CI artifact so the
			// actual chat output can be eyeballed after a Minecraft update. Component
			// rendering changes constantly; a pixel comparison here would only ever
			// produce false failures.
			context.takeScreenshot("serverinsight-chat-output");
		}
	}

	/** Proves the client command entrypoint registered, independently of running it. */
	private static void assertCommandRegistered(ClientGameTestContext context) {
		boolean registered = context.computeOnClient(mc ->
			ClientCommands.getActiveDispatcher() != null
				&& ClientCommands.getActiveDispatcher().getRoot().getChild("serverinsight") != null);

		if (!registered) {
			throw new AssertionError("/serverinsight is missing from the client command dispatcher");
		}
	}

	/**
	 * handleCommands -> PluginScanner.onCommandTree. The command tree packet always
	 * arrives on join, so seeing the fake namespaced command proves both that the inject
	 * ran and that namespace extraction still works.
	 */
	private static void assertCommandTreeMixinFired(ClientGameTestContext context) {
		context.waitFor(mc -> ServerInsightRuntime.INSTANCE.plugins().commandTreeCount() > 0, SLOW_TIMEOUT);

		Set<String> detected = context.computeOnClient(mc -> ServerInsightRuntime.INSTANCE.plugins().combinedPlugins());

		if (!detected.contains("testplugin")) {
			throw new AssertionError("command-tree scan missed the testplugin: namespace, found: " + detected);
		}
	}

	/**
	 * handleSetTime -> TimingTracker. getEstimatedTps() reports 20.0 when starved as
	 * well as when healthy, so the sample count is the only real witness that the
	 * inject ran.
	 */
	private static void assertTimeMixinFired(ClientGameTestContext context) {
		context.waitFor(mc -> ServerInsightRuntime.INSTANCE.timing().sampleCount() >= 2, SLOW_TIMEOUT);

		double tps = context.computeOnClient(mc -> ServerInsightRuntime.INSTANCE.timing().getEstimatedTps());

		// Only the clamp is asserted. The estimate is derived from wall-clock packet
		// spacing, and a CI runner's timing is not stable enough to assert "about 20"
		// without buying a flaky test.
		if (!(tps > 0.0) || tps > 20.0) {
			throw new AssertionError("TPS estimate outside its 0-20 clamp: " + tps);
		}
	}

	/**
	 * Runs the command the way a user does, then waits for tab-completion results to
	 * land. Completion results can only appear if the command executed, reached
	 * printPlugins, sent the suggestion packet, and handleCommandSuggestions fed the
	 * reply back — so this single wait covers the whole async path plus the third mixin.
	 */
	private static void assertCommandRunsAndCompletesScan(ClientGameTestContext context) {
		TestInput input = context.getInput();
		input.pressKey(options -> options.keyChat);
		context.waitTick();
		input.typeChars("/serverinsight");
		input.pressKey(InputConstants.KEY_RETURN);

		context.waitFor(mc -> ServerInsightRuntime.INSTANCE.plugins().completionCount() > 0, SLOW_TIMEOUT);

		Set<String> detected = context.computeOnClient(mc -> ServerInsightRuntime.INSTANCE.plugins().combinedPlugins());

		for (String expected : new String[]{"testpluginalpha", "testpluginbeta"}) {
			if (!detected.contains(expected)) {
				throw new AssertionError("tab-completion scan missed " + expected + ", found: " + detected);
			}
		}
	}
}
