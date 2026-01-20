package dev.flamebeast.serverinsight;

import dev.flamebeast.serverinsight.command.ServerInsightCommand;
import dev.flamebeast.serverinsight.state.ServerInsightRuntime;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ServerInsightClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("Server Insight");

	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			ServerInsightCommand.register(dispatcher)
		);

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			ServerInsightRuntime.INSTANCE.resetForJoin();
			LOGGER.info("Joined server: state reset.");
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ServerInsightRuntime.INSTANCE.resetForDisconnect();
			LOGGER.info("Disconnected: state reset.");
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> ServerInsightRuntime.INSTANCE.tick());

		LOGGER.info("Server Insight initialized.");
	}
}
