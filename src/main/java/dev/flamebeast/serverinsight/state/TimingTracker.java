package dev.flamebeast.serverinsight.state;

import java.util.ArrayDeque;
import java.util.OptionalDouble;

/**
 * Estimates the server's tick rate from the time-update packets it sends.
 *
 * The packet carries the server's gameTime — a raw tick counter — so the estimate is
 * simply ticks elapsed over wall-clock elapsed, and it does not care how often the
 * server chooses to send. An earlier version discarded gameTime and assumed a fixed
 * 20 ticks per packet, which silently produced wrong numbers on any server that sends
 * at a different interval, and reported a confident 20.00 on servers that never send
 * at all.
 *
 * Fed from the client thread only (the mixin injects at TAIL, after the packet has
 * been handed off the netty thread).
 */
public final class TimingTracker {
	private record Sample(long gameTime, long wallMillis) {
	}

	private static final int WINDOW = 20;

	/** Below this the wall-clock divisor is too small for the ratio to be stable. */
	private static final long MIN_SPAN_MILLIS = 1_500L;

	/** A server that stops sending must not leave its last good reading on screen. */
	private static final long STALE_MILLIS = 10_000L;

	private final ArrayDeque<Sample> samples = new ArrayDeque<>();

	public void reset() {
		samples.clear();
	}

	public void onWorldTimeUpdate(long gameTime, long nowMillis) {
		samples.addLast(new Sample(gameTime, nowMillis));

		while (samples.size() > WINDOW) {
			samples.removeFirst();
		}
	}

	/**
	 * Number of packets in the window. getEstimatedTps() cannot distinguish "healthy"
	 * from "no data" on its own, so this is what proves the mixin feeding it is firing.
	 */
	public int sampleCount() {
		return samples.size();
	}

	/**
	 * Empty whenever there is not enough recent data to say something honest — too few
	 * samples, too short a span, or the server went quiet. Callers must render that as
	 * "unknown" rather than substituting a plausible number.
	 */
	public OptionalDouble estimatedTps() {
		Sample first = samples.peekFirst();
		Sample last = samples.peekLast();

		if (first == null || last == null || samples.size() < 2) {
			return OptionalDouble.empty();
		}

		long spanMillis = last.wallMillis() - first.wallMillis();
		if (spanMillis < MIN_SPAN_MILLIS) {
			return OptionalDouble.empty();
		}

		if (System.currentTimeMillis() - last.wallMillis() > STALE_MILLIS) {
			return OptionalDouble.empty();
		}

		// gameTime can jump backwards if the server rewinds it. Refuse rather than guess.
		long ticks = last.gameTime() - first.gameTime();
		if (ticks < 0) {
			return OptionalDouble.empty();
		}

		double tps = ticks * 1000.0 / spanMillis;
		return OptionalDouble.of(Math.max(0.0, Math.min(20.0, tps)));
	}
}
