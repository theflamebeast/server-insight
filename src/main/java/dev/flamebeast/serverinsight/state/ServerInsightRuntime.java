package dev.flamebeast.serverinsight.state;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;

public enum ServerInsightRuntime {
	INSTANCE;

	private final TimingTracker timingTracker = new TimingTracker();
	private final PluginScanner pluginScanner = new PluginScanner();
	private final AddressResolver addressResolver = new AddressResolver();

	public TimingTracker timing() {
		return timingTracker;
	}

	public PluginScanner plugins() {
		return pluginScanner;
	}

	public AddressResolver address() {
		return addressResolver;
	}

	public void resetForJoin() {
		timingTracker.reset();
		pluginScanner.reset();
		addressResolver.reset();
	}

	public void resetForDisconnect() {
		timingTracker.reset();
		pluginScanner.reset();
		addressResolver.reset();
	}

	public void tick() {
		pluginScanner.tick();
	}

	public void onWorldTimeUpdate(long gameTime, long nowMillis) {
		timingTracker.onWorldTimeUpdate(gameTime, nowMillis);
	}

	public void onCommandTree(CommandDispatcher<?> dispatcher) {
		pluginScanner.onCommandTree(dispatcher);
	}

	public void onCommandSuggestions(ClientboundCommandSuggestionsPacket packet) {
		pluginScanner.onCommandSuggestions(packet);
	}
}
