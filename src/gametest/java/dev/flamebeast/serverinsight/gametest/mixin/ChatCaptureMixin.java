package dev.flamebeast.serverinsight.gametest.mixin;

import dev.flamebeast.serverinsight.gametest.CapturedChat;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Test-only tap on the exact method Fabric's client command source writes feedback to.
 *
 * This is the only hook that sees the mod's output: command feedback never reaches
 * ChatListener, so the message events are silent, and ChatComponent keeps its message
 * list private.
 *
 * Lives in src/gametest, so it is never part of the released jar.
 */
@Mixin(ChatComponent.class)
public abstract class ChatCaptureMixin {
	@Inject(method = "addClientSystemMessage", at = @At("HEAD"))
	private void serverinsightGametest_captureChat(Component message, CallbackInfo ci) {
		CapturedChat.record(message);
	}
}
