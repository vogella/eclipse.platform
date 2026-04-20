/*******************************************************************************
 * Copyright (c) 2026 Vogella GmbH and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.ui.examples.buildmonitor;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.ResourcesPlugin;

/**
 * Listens to workspace build events and per-builder build events, and records
 * a session tree that the view can display.
 * <p>
 * One {@link Session} is created for each workspace-level PRE_BUILD / POST_BUILD
 * pair. Inside a session, per-builder events are attributed to their
 * {@link ProjectEntry} and {@link BuilderEntry}. To match PRE / POST events
 * correctly under parallel builds, each thread uses its own stack of
 * in-progress builder entries; a given builder runs synchronously on one
 * thread, so push-on-PRE / pop-on-POST is race-free.
 */
public final class BuildRecorder {

	public interface Listener {
		void sessionsChanged();
	}

	private static final int MAX_SESSIONS = 50;

	private final int mask = IResourceChangeEvent.PRE_BUILD | IResourceChangeEvent.POST_BUILD
			| IResourceChangeEvent.PRE_PROJECT_BUILD | IResourceChangeEvent.POST_PROJECT_BUILD;

	private final IResourceChangeListener eventListener = this::onResourceChangeEvent;

	private final List<Session> sessions = Collections.synchronizedList(new ArrayList<>());
	private final List<Listener> listeners = new CopyOnWriteArrayList<>();

	private final ThreadLocal<Deque<BuilderEntry>> threadStack = ThreadLocal.withInitial(ArrayDeque::new);

	private volatile Session currentSession;

	public void start() {
		ResourcesPlugin.getWorkspace().addResourceChangeListener(eventListener, mask);
	}

	public void stop() {
		ResourcesPlugin.getWorkspace().removeResourceChangeListener(eventListener);
	}

	public void addListener(Listener listener) {
		listeners.add(listener);
	}

	public void removeListener(Listener listener) {
		listeners.remove(listener);
	}

	public List<Session> getSessions() {
		synchronized (sessions) {
			return new ArrayList<>(sessions);
		}
	}

	public void clear() {
		synchronized (sessions) {
			sessions.clear();
		}
		fireChanged();
	}

	private void onResourceChangeEvent(IResourceChangeEvent event) {
		switch (event.getType()) {
			case IResourceChangeEvent.PRE_BUILD -> onWorkspacePreBuild(event);
			case IResourceChangeEvent.POST_BUILD -> onWorkspacePostBuild(event);
			case IResourceChangeEvent.PRE_PROJECT_BUILD -> onProjectPreBuild(event);
			case IResourceChangeEvent.POST_PROJECT_BUILD -> onProjectPostBuild(event);
			default -> { /* ignore */ }
		}
	}

	private void onWorkspacePreBuild(IResourceChangeEvent event) {
		Session session = new Session(System.nanoTime(), Instant.now(), event.getBuildKind());
		currentSession = session;
	}

	private void onWorkspacePostBuild(IResourceChangeEvent event) {
		Session session = currentSession;
		if (session == null) {
			return;
		}
		session.finish(System.nanoTime());
		currentSession = null;
		synchronized (sessions) {
			sessions.add(session);
			while (sessions.size() > MAX_SESSIONS) {
				sessions.remove(0);
			}
		}
		fireChanged();
	}

	private void onProjectPreBuild(IResourceChangeEvent event) {
		Session session = currentSession;
		if (session == null || !(event.getSource() instanceof IProject project)) {
			return;
		}
		BuilderEntry entry = new BuilderEntry(event.getBuilderName(), event.getBuildKind(), System.nanoTime());
		session.addBuilderStart(project, entry);
		threadStack.get().push(entry);
	}

	private void onProjectPostBuild(IResourceChangeEvent event) {
		Deque<BuilderEntry> stack = threadStack.get();
		BuilderEntry entry = stack.pollFirst();
		if (entry == null) {
			return;
		}
		entry.finish(System.nanoTime());
		Session session = currentSession;
		if (session != null && event.getSource() instanceof IProject project) {
			session.noteBuilderEnd(project, entry);
		}
	}

	private void fireChanged() {
		for (Listener l : listeners) {
			try {
				l.sessionsChanged();
			} catch (RuntimeException ignored) {
				// a broken listener must not poison the notification
			}
		}
	}

	public static final class Session {
		private final long startNanos;
		private final Instant startedAt;
		private final int buildKind;
		private final Map<IProject, ProjectEntry> projects = Collections.synchronizedMap(new LinkedHashMap<>());
		private volatile long endNanos = -1;

		Session(long startNanos, Instant startedAt, int buildKind) {
			this.startNanos = startNanos;
			this.startedAt = startedAt;
			this.buildKind = buildKind;
		}

		void addBuilderStart(IProject project, BuilderEntry entry) {
			projects.computeIfAbsent(project, ProjectEntry::new).add(entry, startNanos);
		}

		void noteBuilderEnd(IProject project, BuilderEntry entry) {
			ProjectEntry p = projects.get(project);
			if (p != null) {
				p.noteEnd(entry);
			}
		}

		void finish(long endNanos) {
			this.endNanos = endNanos;
		}

		public Instant getStartedAt() {
			return startedAt;
		}

		public int getBuildKind() {
			return buildKind;
		}

		public long getDurationNanos() {
			return endNanos < 0 ? System.nanoTime() - startNanos : endNanos - startNanos;
		}

		public long getStartNanos() {
			return startNanos;
		}

		public List<ProjectEntry> getProjects() {
			synchronized (projects) {
				return new ArrayList<>(projects.values());
			}
		}
	}

	public static final class ProjectEntry {
		private final IProject project;
		private final List<BuilderEntry> builders = Collections.synchronizedList(new ArrayList<>());
		private volatile long firstStartNanos = Long.MAX_VALUE;
		private volatile long lastEndNanos = Long.MIN_VALUE;
		private volatile long sessionStartNanos;

		ProjectEntry(IProject project) {
			this.project = project;
		}

		synchronized void add(BuilderEntry entry, long sessionStart) {
			this.sessionStartNanos = sessionStart;
			builders.add(entry);
			if (entry.getStartNanos() < firstStartNanos) {
				firstStartNanos = entry.getStartNanos();
			}
		}

		synchronized void noteEnd(BuilderEntry entry) {
			if (entry.getEndNanos() > lastEndNanos) {
				lastEndNanos = entry.getEndNanos();
			}
		}

		public IProject getProject() {
			return project;
		}

		public List<BuilderEntry> getBuilders() {
			synchronized (builders) {
				return new ArrayList<>(builders);
			}
		}

		/**
		 * Pure build time: sum of each builder's (end - start).
		 */
		public long getPureBuildTimeNanos() {
			long sum = 0;
			synchronized (builders) {
				for (BuilderEntry b : builders) {
					sum += b.getDurationNanos();
				}
			}
			return sum;
		}

		/**
		 * Time until ready: from the start of the enclosing workspace build to
		 * the moment this project's last builder finished.
		 */
		public long getTimeUntilReadyNanos() {
			if (lastEndNanos == Long.MIN_VALUE) {
				return 0;
			}
			return lastEndNanos - sessionStartNanos;
		}
	}

	public static final class BuilderEntry {
		private final String builderName;
		private final int buildKind;
		private final long startNanos;
		private volatile long endNanos = -1;

		BuilderEntry(String builderName, int buildKind, long startNanos) {
			this.builderName = builderName;
			this.buildKind = buildKind;
			this.startNanos = startNanos;
		}

		void finish(long endNanos) {
			this.endNanos = endNanos;
		}

		public String getBuilderName() {
			return builderName;
		}

		public int getBuildKind() {
			return buildKind;
		}

		public long getStartNanos() {
			return startNanos;
		}

		public long getEndNanos() {
			return endNanos;
		}

		public long getDurationNanos() {
			return endNanos < 0 ? 0 : endNanos - startNanos;
		}
	}
}
