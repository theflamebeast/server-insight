package dev.flamebeast.serverinsight.detect;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.resources.Identifier;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Server-side mods, inferred from the network channels the server declares it can
 * receive.
 *
 * Every mod that talks to clients registers its channels under its own mod id, and the
 * server announces those channels unprompted during the handshake — so reading them
 * back is entirely passive, and is the mod-side equivalent of the namespaced-command
 * trick the plugin scanner uses.
 *
 * This is a LOWER BOUND, not a mod list. A mod with no client-server networking is
 * invisible here, and the command output has to say so.
 */
public final class ServerMods {
	private ServerMods() {
	}

	/** Namespaces that are the protocol itself rather than somebody's mod. */
	private static final Set<String> IGNORED = Set.of("minecraft", "c");

	/** Collapses the many fabric-* API modules into one entry instead of a wall of them. */
	private static final String FABRIC_API = "fabric-api";

	public static Set<String> detected() {
		Set<Identifier> channels;

		try {
			channels = ClientPlayNetworking.getSendable();
		} catch (IllegalStateException notConnected) {
			return Set.of();
		}

		Set<String> mods = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

		for (Identifier channel : channels) {
			String namespace = channel.getNamespace().toLowerCase(Locale.ROOT);

			if (IGNORED.contains(namespace)) {
				continue;
			}

			mods.add(namespace.equals("fabric") || namespace.startsWith("fabric-") ? FABRIC_API : namespace);
		}

		return mods;
	}
}
