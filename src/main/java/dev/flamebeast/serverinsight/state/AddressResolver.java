package dev.flamebeast.serverinsight.state;

import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;

/**
 * Resolves the connected server's hostname to an IP, off the client thread.
 *
 * InetAddress.getByName() blocks on DNS and used to be called inline while formatting
 * the command output, so a slow or unreachable resolver froze the game for as long as
 * the lookup took. The lookup now starts on join; by the time anyone runs the command
 * the answer is cached, and if it is not the address line simply omits it.
 */
public final class AddressResolver {
	/** The host the current lookup belongs to. Also guards against a stale result landing after a server switch. */
	private volatile String host;
	private volatile String resolved;

	public void reset() {
		host = null;
		resolved = null;
	}

	public void beginResolve(String rawHost) {
		if (rawHost == null || rawHost.isBlank()) {
			return;
		}

		host = rawHost;
		resolved = null;

		CompletableFuture
			.supplyAsync(() -> {
				try {
					return InetAddress.getByName(rawHost).getHostAddress();
				} catch (Exception e) {
					// A failed lookup is not worth surfacing — the address line just
					// shows what the user typed, which is what they care about anyway.
					return null;
				}
			})
			.thenAccept(ip -> {
				if (rawHost.equals(host)) {
					resolved = ip;
				}
			});
	}

	/** Null until the lookup finishes, if it failed, or if we have since joined elsewhere. */
	public String resolvedFor(String rawHost) {
		return rawHost != null && rawHost.equals(host) ? resolved : null;
	}
}
