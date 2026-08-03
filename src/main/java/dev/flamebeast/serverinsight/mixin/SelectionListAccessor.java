package dev.flamebeast.serverinsight.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reaches the scrollbar position on the list widget.
 *
 * The rows are narrower than the list, and the scrollbar sits to the right of both, so
 * a row's own right edge is not far enough right to clear it. scrollBarX() is protected,
 * hence the invoker.
 */
@Mixin(net.minecraft.client.gui.components.AbstractSelectionList.class)
public interface SelectionListAccessor {
	@Invoker("scrollBarX")
	int serverinsight$scrollBarX();
}
