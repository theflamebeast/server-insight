package dev.flamebeast.serverinsight.mixin;

import dev.flamebeast.serverinsight.detect.LocationInfo;
import dev.flamebeast.serverinsight.state.GeoLocator;
import dev.flamebeast.serverinsight.text.LocationTooltip;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the host country's flag beside each entry in the multiplayer server list, with
 * the full geolocation on hover.
 *
 * Runs for every visible entry every frame, so it must stay cheap and non-blocking.
 * GeoLocator answers from cache or returns null and does the work elsewhere, so a
 * missing flag simply means "not known yet" and the list never stutters.
 */
@Mixin(ServerSelectionList.OnlineServerEntry.class)
public abstract class ServerEntryFlagMixin {
	@Shadow
	@Final
	private ServerData serverData;

	private static final int FLAG_WIDTH = 16;
	private static final int FLAG_HEIGHT = 12;

	/** Texture size on disk. Every flag is this size, so one blit works for all of them. */
	private static final int TEXTURE_WIDTH = 32;
	private static final int TEXTURE_HEIGHT = 24;

	private static final int GAP_FROM_SCROLLBAR = 4;

	// RETURN, not TAIL. Vanilla leaves extractContent early for a server that is still
	// being pinged, and TAIL only injects at the final return — so rows in that state
	// never got a flag, which looked like the lookup had failed for them.
	@Inject(method = "extractContent", at = @At("RETURN"))
	private void serverinsight_drawFlag(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
			boolean hovered, float partialTick, CallbackInfo ci) {
		if (serverData == null || serverData.ip == null) {
			return;
		}

		LocationInfo location = GeoLocator.lookup(ServerAddress.parseString(serverData.ip).getHost());
		if (location == null) {
			return;
		}

		// Right of the SCROLLBAR, clear of the whole list widget. The row's own right
		// edge is not far enough — rows are narrower than the list, and the scrollbar
		// sits to the right of both.
		EntryGeometryAccessor geometry = (EntryGeometryAccessor) this;
		AbstractSelectionList<?> list = geometry.serverinsight$list();
		int x = ((SelectionListAccessor) list).serverinsight$scrollBarX() + list.scrollbarWidth() + GAP_FROM_SCROLLBAR;
		int y = geometry.serverinsight$contentYMiddle() - FLAG_HEIGHT / 2;

		// The source region must be the WHOLE texture, or this crops instead of scaling.
		// Passing the destination size as the region drew the top-left 16x12 of a 32x24
		// flag, which looked like a corner of the flag rather than a small one.
		extractor.blit(
			RenderPipelines.GUI_TEXTURED,
			Identifier.fromNamespaceAndPath("serverinsight", "textures/gui/flags/" + location.countryCode() + ".png"),
			x, y,
			0.0F, 0.0F,
			FLAG_WIDTH, FLAG_HEIGHT,
			TEXTURE_WIDTH, TEXTURE_HEIGHT,
			TEXTURE_WIDTH, TEXTURE_HEIGHT
		);

		if (mouseX >= x && mouseX < x + FLAG_WIDTH && mouseY >= y && mouseY < y + FLAG_HEIGHT) {
			extractor.setComponentTooltipForNextFrame(Minecraft.getInstance().font,
				LocationTooltip.lines(location, GeoLocator.localLocation()), mouseX, mouseY);
		}
	}
}
