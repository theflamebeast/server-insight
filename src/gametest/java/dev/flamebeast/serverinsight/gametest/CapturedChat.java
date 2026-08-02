package dev.flamebeast.serverinsight.gametest;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Plain-text record of what the mod actually printed to chat.
 *
 * Client command feedback does not go through ChatListener — Fabric's command source
 * calls Gui.hud.getChat().addClientSystemMessage() directly — so none of the
 * ClientReceiveMessageEvents fire for it. {@link ChatCaptureMixin} taps that method
 * instead, and this is where the lines land.
 *
 * Written from the render thread and read from the gametest thread, hence the
 * synchronization.
 */
public final class CapturedChat {
	private static final List<String> LINES = Collections.synchronizedList(new ArrayList<>());

	private CapturedChat() {
	}

	/** Called from {@code ChatCaptureMixin} on the render thread. */
	public static void record(Component message) {
		LINES.add(message.getString());
	}

	public static void clear() {
		LINES.clear();
	}

	/** Only the mod's own output. Vanilla also pushes things like the chat-trust warning. */
	public static List<String> serverInsightLines() {
		synchronized (LINES) {
			return LINES.stream().filter(line -> line.startsWith("Server Insight")).toList();
		}
	}

	/** True if any of the mod's lines contains every one of {@code needles}, case-insensitively. */
	public static boolean hasLineContaining(String... needles) {
		for (String line : serverInsightLines()) {
			String haystack = line.toLowerCase(Locale.ROOT);
			boolean all = true;

			for (String needle : needles) {
				if (!haystack.contains(needle.toLowerCase(Locale.ROOT))) {
					all = false;
					break;
				}
			}

			if (all) {
				return true;
			}
		}

		return false;
	}
}
