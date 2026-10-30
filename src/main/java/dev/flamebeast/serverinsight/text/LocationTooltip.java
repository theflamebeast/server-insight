package dev.flamebeast.serverinsight.text;

import dev.flamebeast.serverinsight.detect.LocationInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * The one description of a geolocation, shown on the server-list flag and on the
 * command's Location line. There used to be a copy in each, and they had already
 * drifted — only the flag had learned the distance line.
 */
public final class LocationTooltip {
	private LocationTooltip() {
	}

	/**
	 * @param here the client's own location, or null while it is not known — the distance
	 *             line is then simply left out
	 */
	public static List<Component> lines(LocationInfo location, LocationInfo here) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal(location.describePlace()).withStyle(ChatFormatting.WHITE));

		Double km = location.distanceKm(here);
		addLine(lines, "Distance", km == null ? null : Math.round(km) + " km");
		addLine(lines, "IP", location.queriedIp());
		addLine(lines, "ISP", location.isp());

		// Org repeats the ISP often enough that showing both is just noise.
		if (location.org() != null && !location.org().equals(location.isp())) {
			addLine(lines, "Org", location.org());
		}

		addLine(lines, "AS", location.asName());
		addLine(lines, "Timezone", location.timezone());
		return lines;
	}

	/** The same lines as one component, for a chat HoverEvent, which takes exactly one. */
	public static Component joined(LocationInfo location, LocationInfo here) {
		MutableComponent out = Component.empty();
		List<Component> lines = lines(location, here);

		for (int i = 0; i < lines.size(); i++) {
			if (i > 0) {
				out.append("\n");
			}

			out.append(lines.get(i));
		}

		return out;
	}

	private static void addLine(List<Component> lines, String label, String value) {
		if (value == null) {
			return;
		}

		lines.add(Component.literal(label + ": ").withStyle(ChatFormatting.GRAY)
			.append(Component.literal(value).withStyle(ChatFormatting.WHITE)));
	}
}
