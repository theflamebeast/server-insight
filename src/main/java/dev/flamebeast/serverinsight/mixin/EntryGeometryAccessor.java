package dev.flamebeast.serverinsight.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reaches the layout accessors on AbstractSelectionList.Entry.
 *
 * Two things rule out the obvious approaches. The class is protected, so its type
 * cannot be named in source and the entry cannot simply be cast to it. And @Shadow
 * only resolves methods DECLARED on the target class — shadowing an inherited method
 * fails at mixin application with "was not located in the target class", even though
 * the method is public and callable at runtime.
 *
 * Mixing an interface into the superclass and casting the entry to that interface is
 * the way through: the cast is legal because this interface is ours, and the target
 * gains it at load time.
 */
@Mixin(targets = "net.minecraft.client.gui.components.AbstractSelectionList$Entry")
public interface EntryGeometryAccessor {
	@Invoker("getContentRight")
	int serverinsight$contentRight();

	@Invoker("getContentYMiddle")
	int serverinsight$contentYMiddle();

	@Invoker("getX")
	int serverinsight$x();

	@Invoker("getWidth")
	int serverinsight$width();
}
