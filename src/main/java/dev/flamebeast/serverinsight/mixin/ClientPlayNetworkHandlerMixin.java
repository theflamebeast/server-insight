package dev.flamebeast.serverinsight.mixin;

import com.mojang.brigadier.CommandDispatcher;
import dev.flamebeast.serverinsight.state.ServerInsightRuntime;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.commands.CommandSource;
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
	private CommandDispatcher<CommandSource> commands;

	@Inject(method = "handleSetTime", at = @At("HEAD"))
	private void serverinsight_onWorldTimeUpdate(ClientboundSetTimePacket packet, CallbackInfo ci) {
		ServerInsightRuntime.INSTANCE.onWorldTimeUpdateMillis(System.currentTimeMillis());
	}

	@Inject(method = "handleCommands", at = @At("TAIL"))
	private void serverinsight_onCommandTree(ClientboundCommandsPacket packet, CallbackInfo ci) {
		ServerInsightRuntime.INSTANCE.onCommandTree(this.commands);
	}

	@Inject(method = "handleTabListQueryReply", at = @At("TAIL"))
	private void serverinsight_onCommandSuggestions(ClientboundCommandSuggestionsPacket packet, CallbackInfo ci) {
		ServerInsightRuntime.INSTANCE.onCommandSuggestions(packet);
	}
}