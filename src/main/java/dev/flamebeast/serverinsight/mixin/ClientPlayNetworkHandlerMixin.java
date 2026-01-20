package dev.flamebeast.serverinsight.mixin;

import com.mojang.brigadier.CommandDispatcher;
import dev.flamebeast.serverinsight.state.ServerInsightRuntime;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.command.CommandSource;
import net.minecraft.network.packet.s2c.play.CommandSuggestionsS2CPacket;
import net.minecraft.network.packet.s2c.play.CommandTreeS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
	@Shadow
	private CommandDispatcher<CommandSource> commandDispatcher;

	@Inject(method = "onWorldTimeUpdate", at = @At("HEAD"))
	private void serverinsight_onWorldTimeUpdate(WorldTimeUpdateS2CPacket packet, CallbackInfo ci) {
		ServerInsightRuntime.INSTANCE.onWorldTimeUpdateMillis(System.currentTimeMillis());
	}

	@Inject(method = "onCommandTree", at = @At("TAIL"))
	private void serverinsight_onCommandTree(CommandTreeS2CPacket packet, CallbackInfo ci) {
		ServerInsightRuntime.INSTANCE.onCommandTree(this.commandDispatcher);
	}

	@Inject(method = "onCommandSuggestions", at = @At("TAIL"))
	private void serverinsight_onCommandSuggestions(CommandSuggestionsS2CPacket packet, CallbackInfo ci) {
		ServerInsightRuntime.INSTANCE.onCommandSuggestions(packet);
	}
}
