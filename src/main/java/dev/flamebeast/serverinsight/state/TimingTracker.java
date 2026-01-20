package dev.flamebeast.serverinsight.state;

import java.util.ArrayDeque;

public final class TimingTracker {
	// Stores the arrival timestamps of recent server time updates.
	private final ArrayDeque<Long> recentUpdatesMillis = new ArrayDeque<>();
	private static final int WINDOW = 20;
	private long joinMillis = -1;

	public void reset() {
		recentUpdatesMillis.clear();
		joinMillis = System.currentTimeMillis();
	}

	public void onWorldTimeUpdateMillis(long nowMillis) {
		if (joinMillis < 0) {
			joinMillis = nowMillis;
		}

		recentUpdatesMillis.addLast(nowMillis);
		while (recentUpdatesMillis.size() > WINDOW) {
			recentUpdatesMillis.removeFirst();
		}
	}

	public double getEstimatedTps() {
		// Early after join, assume 20 to avoid noisy values.
		if (joinMillis > 0 && System.currentTimeMillis() - joinMillis < 4000) {
			return 20.0;
		}

		if (recentUpdatesMillis.size() < 2) {
			return 20.0;
		}

		Long first = recentUpdatesMillis.peekFirst();
		Long last = recentUpdatesMillis.peekLast();
		if (first == null || last == null || last <= first) {
			return 20.0;
		}

		int intervals = recentUpdatesMillis.size() - 1;
		double elapsedSeconds = (last - first) / 1000.0;
		if (elapsedSeconds <= 0.0) {
			return 20.0;
		}

		// World time updates typically represent ~20 ticks worth of time.
		double ticks = intervals * 20.0;
		double tps = ticks / elapsedSeconds;
		return Math.max(0.0, Math.min(20.0, tps));
	}
}
