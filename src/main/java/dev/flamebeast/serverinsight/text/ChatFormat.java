package dev.flamebeast.serverinsight.text;

import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;

public final class ChatFormat {
	private ChatFormat() {
	}

	public static MutableText prefix() {
		MutableText brand = gradientBold("Server Insight", 0xFF8800, 0xCC0055);
		return brand.append(Text.literal(" ").formatted(Formatting.DARK_GRAY));
	}

	private static MutableText gradientBold(String text, int startRgb, int endRgb) {
		if (text.isEmpty()) {
			return Text.empty();
		}

		MutableText out = Text.empty();
		int n = Math.max(1, text.length() - 1);
		for (int i = 0; i < text.length(); i++) {
			double t = (double) i / (double) n;
			int rgb = lerpRgb(startRgb, endRgb, t);
			out.append(
				Text.literal(String.valueOf(text.charAt(i)))
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

	public static MutableText header(String title) {
		return prefix()
			.append(Text.literal(title).formatted(Formatting.YELLOW));
	}

	public static MutableText label(String label) {
		return Text.literal("• ").formatted(Formatting.DARK_GRAY)
			.append(Text.literal(label).formatted(Formatting.GRAY));
	}

	public static MutableText kv(String key, Text value) {
		return prefix()
			.append(label(key))
			.append(Text.literal(": ").formatted(Formatting.DARK_GRAY))
			.append(value);
	}
}
