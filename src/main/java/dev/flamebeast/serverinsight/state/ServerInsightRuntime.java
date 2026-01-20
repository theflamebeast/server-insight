package dev.flamebeast.serverinsight.state;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.command.CommandSource;
import net.minecraft.network.packet.s2c.play.CommandSuggestionsS2CPacket;

public enum ServerInsightRuntime {
	INSTANCE;

	private final TimingTracker timingTracker = new TimingTracker();
	private final PluginScanner pluginScanner = new PluginScanner();

	public TimingTracker timing() {
		return timingTracker;
	}

	public PluginScanner plugins() {
		return pluginScanner;
	}

	public void resetForJoin() {
		timingTracker.reset();
		pluginScanner.reset();
	}

	public void resetForDisconnect() {
		timingTracker.reset();
		pluginScanner.reset();
	}

	public void tick() {
		pluginScanner.tick();
	}

	public void onWorldTimeUpdateMillis(long nowMillis) {
		timingTracker.onWorldTimeUpdateMillis(nowMillis);
	}

	public void onCommandTree(CommandDispatcher<CommandSource> dispatcher) {
		pluginScanner.onCommandTree(dispatcher);
	}

	public void onCommandSuggestions(CommandSuggestionsS2CPacket packet) {
		pluginScanner.onCommandSuggestions(packet);
	}
}
