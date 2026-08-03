package dev.flamebeast.serverinsight.mixin;

import dev.flamebeast.serverinsight.detect.LocationInfo;
import dev.flamebeast.serverinsight.state.GeoLocator;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the host country's flag on each entry in the multiplayer server list, with the
 * full geolocation on hover.
 *
 * Injected at TAIL so the flag lands on top of the entry vanilla just drew. Everything
 * here has to be cheap and non-blocking: this runs for every visible entry every frame.
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

	/**
	 * Width of the ping bars vanilla draws hard against the row's right edge, plus a
	 * gap. The flag sits immediately left of them: that is as far right as it can go
	 * without drawing on top of the ping indicator.
	 */
	private static final int RIGHT_MARGIN = 18;

	// RETURN, not TAIL. Vanilla leaves extractContent early for a server that is still
	// being pinged, and TAIL only injects at the final return — so rows in that state
	// never got a flag, which looked like the lookup had failed for them.
	@Inject(method = "extractContent", at = @At("RETURN"))
	private void serverinsight_drawFlag(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
			boolean hovered, float partialTick, CallbackInfo ci) {
		if (serverData == null || serverData.ip == null) {
			return;
		}

		String host = ServerAddress.parseString(serverData.ip).getHost();
		LocationInfo location = GeoLocator.lookup(host);
		if (location == null) {
			return;
		}

		// Hard against the right edge of the row, vertically centred.
		EntryGeometryAccessor geometry = (EntryGeometryAccessor) this;
		int x = geometry.serverinsight$x() + geometry.serverinsight$width() - FLAG_WIDTH - RIGHT_MARGIN;
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
			extractor.setComponentTooltipForNextFrame(Minecraft.getInstance().font, tooltip(location), mouseX, mouseY);
		}
	}

	private static List<Component> tooltip(LocationInfo location) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal(location.describePlace()).withStyle(ChatFormatting.WHITE));

		addLine(lines, "IP", location.queriedIp());
		addLine(lines, "ISP", location.isp());

		// Org repeats the ISP often enough that showing both is just noise.
		if (location.org() != null && !location.org().equals(location.isp())) {
			addLine(lines, "Org", location.org());
		}

		addLine(lines, "AS", location.asName());
		addLine(lines, "Timezone", location.timezone());

		lines.add(Component.literal("Where the address points, not necessarily the host")
			.withStyle(ChatFormatting.DARK_GRAY));
		return lines;
	}

	private static void addLine(List<Component> lines, String label, String value) {
		if (value == null) {
			return;
		}

		lines.add(Component.literal(label + ": ").withStyle(ChatFormatting.GRAY)
			.append(Component.literal(value).withStyle(ChatFormatting.WHITE)));
	}
}
