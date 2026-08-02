package dev.flamebeast.serverinsight.gametest;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;

/**
 * Makes a vanilla dedicated server look, to the client, like a plugin server.
 *
 * PluginScanner has two independent detection paths and a vanilla server exercises
 * neither — it has no namespaced commands and no /version. This registers the minimum
 * that makes both observable:
 *
 *   - "testplugin:ping"  a namespaced root node, which is what the command-tree scan reads
 *   - "version <plugin>" whose argument suggests two names, which is what the
 *                        tab-completion probe reads
 *
 * Runs on both sides ("main" entrypoint) because commands are registered server-side.
 * It must NOT touch any dev.flamebeast.serverinsight class: Server Insight itself is
 * environment "client" and is absent from the dedicated server entirely.
 */
public final class FakePluginCommands implements ModInitializer {
	/**
	 * Deliberately mixed-case. PluginScanner lowercases everything it detects, and
	 * asserting on the lowercased form is what proves it still does.
	 */
	private static final String[] SUGGESTED_PLUGINS = {"TestPluginAlpha", "TestPluginBeta"};

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			// The scanner only probes a node named one of its VERSION_ALIASES and reads
			// whatever the server suggests after it.
			//
			// The plugin names are LITERAL CHILDREN rather than an argument with a
			// custom suggests() on purpose. An unregistered SuggestionProvider is not
			// serializable into ClientboundCommandsPacket, and Minecraft drops the whole
			// branch rather than degrading it to ask_server — /version never reached the
			// client at all, versionAlias stayed null, and the scan silently no-opped.
			// Literal children serialize fine and the server still answers the
			// suggestion request with them, which is the path being tested.
			LiteralArgumentBuilder<CommandSourceStack> version =
				LiteralArgumentBuilder.<CommandSourceStack>literal("version").executes(ctx -> 1);

			for (String plugin : SUGGESTED_PLUGINS) {
				version.then(LiteralArgumentBuilder.<CommandSourceStack>literal(plugin).executes(ctx -> 1));
			}

			dispatcher.register(version);

			// A node has to be executable to be sent to the client at all, so this needs
			// its own executes() even though nothing ever runs it.
			dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("testplugin:ping")
				.executes(ctx -> 1));
		});
	}
}
