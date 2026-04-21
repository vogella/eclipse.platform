/*******************************************************************************
 * Local-only startup tracer for measuring Eclipse platform startup phases.
 * NOT FOR UPSTREAM MERGE. Always-on; writes to ${user.home}/.eclipse/startup-trace.csv.
 *
 * Lives in equinox.common so it is visible to every downstream bundle that
 * Require-Bundles org.eclipse.equinox.common (directly or via the split package
 * contributed by org.eclipse.core.runtime).
 *
 * Flush strategy:
 *   - A daemon ScheduledExecutorService drains buffered entries to the CSV
 *     every few seconds so abnormal exits (kill -9, Runtime.halt, exec-restart,
 *     JVM crash) lose at most the last interval instead of the whole run.
 *   - A JVM shutdown hook does a final drain and prints the cumulative summary.
 *   - Callers may invoke {@link #flush()} explicitly from known quiesce points
 *     (e.g. Activator.stop, Workbench.close) for deterministic end-of-run data.
 *******************************************************************************/
package org.eclipse.core.internal.runtime;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Always-on lightweight startup tracer. Uses only {@code java.*} APIs so it is
 * safe to call from the earliest startup phases before any Eclipse plug-in is
 * activated. All call sites share one static queue and one {@code RUN_ID}, so
 * contributions from every bundle end up in a single CSV under one run.
 */
public final class StartupTrace {

	/**
	 * Hardcoded to {@code true} for this tracing build. Exposed so callers may
	 * guard expensive trace-building code, though in practice begin/record are
	 * cheap enough that gating is unnecessary.
	 */
	public static final boolean ENABLED = true;

	/** How often the background daemon drains the queue to disk. */
	private static final long FLUSH_INTERVAL_SECONDS = 2L;

	private static final Path OUTPUT_DIR = Path.of(System.getProperty("user.home"), ".eclipse"); //$NON-NLS-1$ //$NON-NLS-2$
	private static final Path OUTPUT_CSV = OUTPUT_DIR.resolve("startup-trace.csv"); //$NON-NLS-1$

	private static final ConcurrentLinkedQueue<Entry> ENTRIES = new ConcurrentLinkedQueue<>();
	private static final AtomicLong SEQ = new AtomicLong();
	private static final AtomicLong TOTAL_WRITTEN = new AtomicLong();
	private static final AtomicBoolean SUMMARY_PRINTED = new AtomicBoolean();
	private static final Object WRITE_LOCK = new Object();
	private static final ConcurrentHashMap<String, long[]> CUMULATIVE = new ConcurrentHashMap<>();
	private static final String RUN_ID = Long.toHexString(System.currentTimeMillis()) + "-" //$NON-NLS-1$
			+ Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xFFFFFFFFL);

	private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "StartupTrace-periodic"); //$NON-NLS-1$
		t.setDaemon(true);
		return t;
	});

	static {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				SCHEDULER.shutdownNow();
			} catch (RuntimeException ignored) {
				// best-effort
			}
			flushInternal("shutdown"); //$NON-NLS-1$
			printSummaryOnce();
		}, "StartupTrace-shutdown")); //$NON-NLS-1$

		SCHEDULER.scheduleWithFixedDelay(() -> {
			try {
				flushInternal("periodic"); //$NON-NLS-1$
			} catch (RuntimeException ignored) {
				// never let a write failure kill the scheduler thread
			}
		}, FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);

		System.out.println("[StartupTrace] enabled, runId=" + RUN_ID //$NON-NLS-1$
				+ ", periodicFlush=" + FLUSH_INTERVAL_SECONDS + "s"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private StartupTrace() {
	}

	/** Returns a start timestamp (ns). */
	public static long begin() {
		return ENABLED ? System.nanoTime() : 0L;
	}

	/** Records a finished span. */
	public static void record(String phase, long startNanos) {
		if (!ENABLED) {
			return;
		}
		long end = System.nanoTime();
		ENTRIES.add(new Entry(SEQ.getAndIncrement(), phase, startNanos, end, Thread.currentThread().getName()));
	}

	/** Convenience: time a Runnable. */
	public static void time(String phase, Runnable r) {
		if (!ENABLED) {
			r.run();
			return;
		}
		long t = System.nanoTime();
		try {
			r.run();
		} finally {
			record(phase, t);
		}
	}

	/**
	 * Drains any buffered entries to the CSV immediately. Safe to call from any
	 * thread, any number of times. Does not print the cumulative summary
	 * (reserved for shutdown). Intended for known quiesce points such as
	 * {@code BundleActivator.stop} or {@code Workbench.close}.
	 */
	public static void flush() {
		flushInternal("flush"); //$NON-NLS-1$
	}

	/**
	 * Back-compat entry point used by the JVM shutdown hook. Drains, then prints
	 * the cumulative summary exactly once per JVM.
	 */
	public static void dump() {
		flushInternal("dump"); //$NON-NLS-1$
		printSummaryOnce();
	}

	private static void flushInternal(String reason) {
		List<Entry> drained = new ArrayList<>();
		Entry e;
		while ((e = ENTRIES.poll()) != null) {
			drained.add(e);
		}
		if (drained.isEmpty()) {
			return;
		}
		drained.sort(Comparator.comparingLong(en -> en.seq));

		synchronized (WRITE_LOCK) {
			try {
				Files.createDirectories(OUTPUT_DIR);
				boolean writeHeader = !Files.exists(OUTPUT_CSV);
				try (BufferedWriter w = Files.newBufferedWriter(OUTPUT_CSV, StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
					if (writeHeader) {
						w.write("runId,seq,phase,startNs,endNs,durationUs,thread\n"); //$NON-NLS-1$
					}
					for (Entry en : drained) {
						long durNs = en.endNs - en.startNs;
						long durUs = durNs / 1000L;
						long[] agg = CUMULATIVE.computeIfAbsent(en.phase, k -> new long[2]);
						agg[0] += durNs;
						agg[1] += 1;
						w.write(RUN_ID);
						w.write(',');
						w.write(Long.toString(en.seq));
						w.write(',');
						w.write(csvEscape(en.phase));
						w.write(',');
						w.write(Long.toString(en.startNs));
						w.write(',');
						w.write(Long.toString(en.endNs));
						w.write(',');
						w.write(Long.toString(durUs));
						w.write(',');
						w.write(csvEscape(en.thread));
						w.write('\n');
					}
				}
				long total = TOTAL_WRITTEN.addAndGet(drained.size());
				System.out.println("[StartupTrace] wrote " + drained.size() //$NON-NLS-1$
						+ " entries for runId=" + RUN_ID //$NON-NLS-1$
						+ " (reason=" + reason //$NON-NLS-1$
						+ ", totalWritten=" + total + ")"); //$NON-NLS-1$ //$NON-NLS-2$
			} catch (IOException ex) {
				System.err.println("[StartupTrace] failed to flush (reason=" + reason + "): " + ex); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
	}

	private static void printSummaryOnce() {
		if (!SUMMARY_PRINTED.compareAndSet(false, true)) {
			return;
		}
		List<Map.Entry<String, long[]>> sorted = new ArrayList<>(CUMULATIVE.entrySet());
		sorted.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
		System.out.println("[StartupTrace] runId=" + RUN_ID //$NON-NLS-1$
				+ " totalEntries=" + TOTAL_WRITTEN.get() //$NON-NLS-1$
				+ " csv=" + OUTPUT_CSV); //$NON-NLS-1$
		System.out.println("[StartupTrace] top phases by cumulative time:"); //$NON-NLS-1$
		System.out.printf("  %10s  %5s  %s%n", "cum_ms", "count", "phase"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		int n = Math.min(40, sorted.size());
		for (int i = 0; i < n; i++) {
			Map.Entry<String, long[]> m = sorted.get(i);
			double ms = m.getValue()[0] / 1_000_000.0;
			long count = m.getValue()[1];
			System.out.printf("  %10.3f  %5d  %s%n", ms, count, m.getKey()); //$NON-NLS-1$
		}
	}

	private static String csvEscape(String s) {
		if (s == null) {
			return ""; //$NON-NLS-1$
		}
		if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0) {
			return s;
		}
		return "\"" + s.replace("\"", "\"\"") + "\""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}

	private static final class Entry {
		final long seq;
		final String phase;
		final long startNs;
		final long endNs;
		final String thread;

		Entry(long seq, String phase, long startNs, long endNs, String thread) {
			this.seq = seq;
			this.phase = phase;
			this.startNs = startNs;
			this.endNs = endNs;
			this.thread = thread;
		}
	}
}
