package dev.flamebeast.serverinsight.text;

import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.ChatFormatting;

public final class ChatFormat {
	private ChatFormat() {
	}

	public static MutableComponent prefix() {
		MutableComponent brand = gradientBold("Server Insight", 0xFF8800, 0xCC0055);
		return brand.append(Component.literal(" ").withStyle(ChatFormatting.DARK_GRAY));
	}

	private static MutableComponent gradientBold(String text, int startRgb, int endRgb) {
		if (text.isEmpty()) {
			return Component.empty();
		}

		MutableComponent out = Component.empty();
		int n = Math.max(1, text.length() - 1);
		for (int i = 0; i < text.length(); i++) {
			double t = (double) i / (double) n;
			int rgb = lerpRgb(startRgb, endRgb, t);
			out.append(
				Component.literal(String.valueOf(text.charAt(i)))
					.setStyle(out.getStyle().withColor(TextColor.fromRgb(rgb)).withBold(true))
			);
		}
		return out;
	}

	private static int lerpRgb(int a, int b, double t) {
		int ar = (a >> 16) & 0xFF;
		int ag = (a >> 8) & 0xFF;
		int ab = a & 0xFF;
		int br = (b >> 16) & 0xFF;
		int bg = (b >> 8) & 0xFF;
		int bb = b & 0xFF;
		int rr = (int) Math.round(ar + (br - ar) * t);
		int rg = (int) Math.round(ag + (bg - ag) * t);
		int rb = (int) Math.round(ab + (bb - ab) * t);
		return (rr << 16) | (rg << 8) | rb;
	}

	public static MutableComponent header(String title) {
		return prefix()
			.append(Component.literal(title).withStyle(ChatFormatting.YELLOW));
	}

	public static MutableComponent label(String label) {
		return Component.literal("• ").withStyle(ChatFormatting.DARK_GRAY)
			.append(Component.literal(label).withStyle(ChatFormatting.GRAY));
	}

	public static MutableComponent kv(String key, Component value) {
		return prefix()
			.append(label(key))
			.append(Component.literal(": ").withStyle(ChatFormatting.DARK_GRAY))
			.append(value);
	}
}