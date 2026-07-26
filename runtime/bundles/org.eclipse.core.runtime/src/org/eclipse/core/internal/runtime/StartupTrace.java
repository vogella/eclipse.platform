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

	/**
	 * Prompt written once at the top of a freshly-created CSV. Lets the file be
	 * pasted directly into a large-context AI for filtering and summarization
	 * before downstream bottleneck analysis. Each instruction line is prefixed
	 * with {@code # } so the body is still grep/awk-filterable as
	 * {@code grep -v '^#' startup-trace.csv}.
	 */
	private static final String ANALYSIS_PROMPT = """
			# === AI ANALYSIS PROMPT — read this before processing the CSV below ===
			#
			# This file is a startup-time span trace from the Eclipse IDE platform
			# runtime, produced by org.eclipse.core.internal.runtime.StartupTrace.
			# Each line after the blank line is a CSV row with this schema:
			#   runId,seq,phase,startNs,endNs,durationUs,thread
			#
			# Field semantics
			# - runId:    short hex token identifying one JVM startup. The file
			#             accumulates across runs; multiple runIds may be present.
			# - seq:      per-run monotonically increasing entry sequence (not
			#             globally unique across runs).
			# - phase:    span name. '/' separates parent and child spans, e.g.
			#               "Workspace.open"                              parent
			#               "Workspace.open/setExplicitWorkspaceEncoding" child
			#             A phase may appear many times in one run (loop bodies).
			# - startNs,
			#   endNs:    System.nanoTime() values. Comparable ONLY within one
			#             runId. Not wall-clock; not comparable across runs.
			# - durationUs: pre-computed (endNs - startNs) / 1000.
			# - thread:   thread that recorded the span.
			#
			# Zero-duration entries (startNs == endNs) are CHECKPOINTS emitted by
			# StartupTrace.mark(name), not spans. Treat them as timestamps.
			#
			# The trace is frozen when the 'startup complete' checkpoint is
			# recorded. So the last row in the file corresponds to end-of-startup;
			# nothing after that is captured. The whole file is "startup data";
			# you do not need to filter out a steady-state tail.
			#
			# === YOUR TASK ===
			#
			# Produce a compact per-run analysis summary that a downstream AI will
			# consume as the input for bottleneck analysis. Do NOT echo raw CSV.
			# Output strict Markdown only, using tables.
			#
			# For EACH runId, emit a "## Run <runId>" section containing:
			#
			# 1. Run overview: total observed wall time (max(endNs) - min(startNs)
			#    in ms), distinct phase count, total entry count, the set of
			#    threads that recorded spans, and a table of all mark()
			#    checkpoints with their offset-from-run-start in ms.
			#
			# 2. Top 20 phases by cumulative duration. Columns:
			#    phase, total_ms, calls, mean_ms, max_ms. Sort by total_ms desc.
			#
			# 3. Hot leaf phases: top 10 phases that are NOT a prefix of any other
			#    phase in the same run, by cumulative ms. Same columns as (2).
			#
			# 4. Parent-vs-children gaps. For each phase whose name is a prefix of
			#    at least one other phase, compute
			#      self_time = parent_total - sum(direct_child_totals)
			#    Report parents with self_time > 50 ms. Columns:
			#    parent, parent_ms, summed_children_ms, gap_ms. This identifies
			#    unaccounted time INSIDE a span that needs further sub-division.
			#
			# 5. Outliers: for any phase with call_count >= 3, list calls whose
			#    duration is > 2x the mean of the other calls of the same phase.
			#    Columns: phase, outlier_ms, mean_of_others_ms.
			#
			# Then, if the file contains >= 2 runIds, a final
			# "## Cross-run comparison" section with the 10 phases having the
			# highest p95/median ratio (most variable). Columns:
			# phase, runs, median_ms, p95_ms, min_ms, max_ms.
			#
			# Do NOT speculate on root causes — that is the downstream AI's job.
			# Stick to measurements. Do not normalize or deduplicate phase names.
			# Do not include raw entry-level dumps in the summary.
			#
			# === END PROMPT — CSV data follows ===

			""";

	private static final ConcurrentLinkedQueue<Entry> ENTRIES = new ConcurrentLinkedQueue<>();
	private static final AtomicLong SEQ = new AtomicLong();
	private static final AtomicLong TOTAL_WRITTEN = new AtomicLong();
	private static final AtomicBoolean SUMMARY_PRINTED = new AtomicBoolean();
	/** True until the first flush of this JVM; first flush truncates any prior file. */
	private static final AtomicBoolean FIRST_FLUSH = new AtomicBoolean(true);
	/**
	 * Sentinel mark name that, when passed to {@link #mark(String)}, freezes the
	 * trace: stops the periodic flush daemon, drains the queue one last time,
	 * and turns all subsequent {@link #begin()}, {@link #record(String, long)},
	 * and {@link #mark(String)} calls into no-ops. Lets the CSV stop at end of
	 * startup so runtime activity is not mixed in.
	 */
	public static final String END_OF_STARTUP_MARK = "startup complete"; //$NON-NLS-1$
	/** Flipped true by {@link #freeze()}; gates all further trace recording. */
	private static final AtomicBoolean FROZEN = new AtomicBoolean(false);
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
		return (ENABLED && !FROZEN.get()) ? System.nanoTime() : 0L;
	}

	/** Records a finished span. */
	public static void record(String phase, long startNanos) {
		if (!ENABLED || FROZEN.get()) {
			return;
		}
		long end = System.nanoTime();
		ENTRIES.add(new Entry(SEQ.getAndIncrement(), phase, startNanos, end, Thread.currentThread().getName()));
	}

	/**
	 * Records an instantaneous marker: startNs == endNs, durationUs == 0.
	 * Useful for checkpoints like "startup complete" where the timestamp is
	 * what matters, not a duration. Passing {@link #END_OF_STARTUP_MARK}
	 * freezes the trace after recording the marker.
	 */
	public static void mark(String phase) {
		if (!ENABLED || FROZEN.get()) {
			return;
		}
		long now = System.nanoTime();
		ENTRIES.add(new Entry(SEQ.getAndIncrement(), phase, now, now, Thread.currentThread().getName()));
		if (END_OF_STARTUP_MARK.equals(phase)) {
			freeze();
		}
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

	/**
	 * Stops the periodic daemon, drains the queue one last time, and gates all
	 * future {@link #begin()} / {@link #record(String, long)} / {@link #mark(String)}
	 * calls as no-ops. Idempotent and thread-safe; only the first invocation
	 * does the work.
	 */
	private static void freeze() {
		if (!FROZEN.compareAndSet(false, true)) {
			return;
		}
		try {
			SCHEDULER.shutdownNow();
		} catch (RuntimeException ignored) {
			// best-effort
		}
		flushInternal("freeze"); //$NON-NLS-1$
		System.out.println("[StartupTrace] frozen at '" + END_OF_STARTUP_MARK //$NON-NLS-1$
				+ "' mark; further begin/record/mark calls are no-ops, runId=" + RUN_ID); //$NON-NLS-1$
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
				boolean firstFlush = FIRST_FLUSH.getAndSet(false);
				StandardOpenOption mode = firstFlush ? StandardOpenOption.TRUNCATE_EXISTING
						: StandardOpenOption.APPEND;
				try (BufferedWriter w = Files.newBufferedWriter(OUTPUT_CSV, StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, mode)) {
					if (firstFlush) {
						w.write(ANALYSIS_PROMPT);
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
