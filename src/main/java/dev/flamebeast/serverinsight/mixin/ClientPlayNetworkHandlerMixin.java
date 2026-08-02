package dev.flamebeast.serverinsight.mixin;

import com.mojang.brigadier.CommandDispatcher;
import dev.flamebeast.serverinsight.state.ServerInsightRuntime;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPlayNetworkHandlerMixin {
	@Shadow
	private CommandDispatcher<ClientSuggestionProvider> commands;

	/**
	 * TAIL, not HEAD. handleSetTime starts with PacketUtils.ensureRunningOnSameThread,
	 * which throws to defer the packet when it arrives on the netty thread and lets it
	 * run again on the client thread. A HEAD inject therefore fired twice per packet
	 * AND mutated the tracker off-thread; TAIL is only reached on the client-thread
	 * pass, because the netty pass leaves via that exception.
	 */
	@Inject(method = "handleSetTime", at = @At("TAIL"))
	private void serverinsight_onWorldTimeUpdate(ClientboundSetTimePacket packet, CallbackInfo ci) {
		ServerInsightRuntime.INSTANCE.onWorldTimeUpdate(packet.gameTime(), System.currentTimeMillis());
	}

	@Inject(method = "handleCommands", at = @At("TAIL"))
	private void serverinsight_onCommandTree(ClientboundCommandsPacket packet, CallbackInfo ci) {
		ServerInsightRuntime.INSTANCE.onCommandTree(this.commands);
	}

	@Inject(method = "handleCommandSuggestions", at = @At("TAIL"))
	private void serverinsight_onCommandSuggestions(ClientboundCommandSuggestionsPacket packet, CallbackInfo ci) {
		ServerInsightRuntime.INSTANCE.onCommandSuggestions(packet);
	}
}
