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

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.examples.buildmonitor.BuildRecorder.BuilderEntry;
import org.eclipse.ui.examples.buildmonitor.BuildRecorder.ProjectEntry;
import org.eclipse.ui.examples.buildmonitor.BuildRecorder.Session;
import org.eclipse.ui.part.ViewPart;

/**
 * View that shows recent build sessions with per-project and per-builder timing.
 * Sessions are top-level rows; expand to see per-project aggregates, expand again
 * to see individual builder runs.
 */
public class BuildMonitorView extends ViewPart {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

	private BuildRecorder recorder;
	private BuildRecorder.Listener recorderListener;
	private TreeViewer viewer;

	@Override
	public void createPartControl(Composite parent) {
		recorder = new BuildRecorder();
		recorder.start();

		viewer = new TreeViewer(parent, SWT.FULL_SELECTION | SWT.H_SCROLL | SWT.V_SCROLL);
		Tree tree = viewer.getTree();
		tree.setHeaderVisible(true);
		tree.setLinesVisible(true);

		addColumn("Build / Project / Builder", 320, new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				if (element instanceof Session s) {
					return "Build " + TIME.format(s.getStartedAt()) + " — " + kindLabel(s.getBuildKind());
				}
				if (element instanceof ProjectEntry p) {
					return p.getProject().getName();
				}
				if (element instanceof BuilderEntry b) {
					return b.getBuilderName() == null ? "<unknown>" : b.getBuilderName();
				}
				return String.valueOf(element);
			}
		});
		addColumn("Pure build time", 120, new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				if (element instanceof Session s) {
					long sum = 0;
					for (ProjectEntry p : s.getProjects()) {
						sum += p.getPureBuildTimeNanos();
					}
					return formatMillis(sum);
				}
				if (element instanceof ProjectEntry p) {
					return formatMillis(p.getPureBuildTimeNanos());
				}
				if (element instanceof BuilderEntry b) {
					return formatMillis(b.getDurationNanos());
				}
				return "";
			}
		});
		addColumn("Time until ready", 140, new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				if (element instanceof Session s) {
					return formatMillis(s.getDurationNanos());
				}
				if (element instanceof ProjectEntry p) {
					return formatMillis(p.getTimeUntilReadyNanos());
				}
				return "";
			}
		});

		viewer.setContentProvider(new BuildMonitorContentProvider());
		viewer.setInput(recorder);

		recorderListener = this::refreshAsync;
		recorder.addListener(recorderListener);

		IToolBarManager toolBar = getViewSite().getActionBars().getToolBarManager();
		toolBar.add(new Action("Clear") {
			@Override
			public void run() {
				recorder.clear();
			}
		});
	}

	@Override
	public void setFocus() {
		viewer.getControl().setFocus();
	}

	@Override
	public void dispose() {
		if (recorder != null) {
			if (recorderListener != null) {
				recorder.removeListener(recorderListener);
			}
			recorder.stop();
		}
		super.dispose();
	}

	private void addColumn(String title, int width, ColumnLabelProvider provider) {
		TreeViewerColumn col = new TreeViewerColumn(viewer, SWT.NONE);
		col.getColumn().setText(title);
		col.getColumn().setWidth(width);
		col.setLabelProvider(provider);
	}

	private void refreshAsync() {
		Display display = viewer.getControl().getDisplay();
		if (display.isDisposed()) {
			return;
		}
		display.asyncExec(() -> {
			if (!viewer.getControl().isDisposed()) {
				viewer.refresh();
			}
		});
	}

	private static String formatMillis(long nanos) {
		if (nanos <= 0) {
			return "—";
		}
		double ms = nanos / 1_000_000.0;
		if (ms < 1) {
			return String.format("%.2f ms", ms);
		}
		if (ms < 1000) {
			return String.format("%.0f ms", ms);
		}
		return String.format("%.2f s", ms / 1000.0);
	}

	private static boolean builderDidWork(BuilderEntry b) {
		return b.getDurationNanos() >= SKIPPED_BUILDER_THRESHOLD_NANOS;
	}

	private static boolean projectDidWork(ProjectEntry p) {
		return p.getBuilders().stream().anyMatch(BuildMonitorView::builderDidWork);
	}

	private static String kindLabel(int kind) {
		return switch (kind) {
			case IncrementalProjectBuilder.FULL_BUILD -> "FULL";
			case IncrementalProjectBuilder.INCREMENTAL_BUILD -> "INCREMENTAL";
			case IncrementalProjectBuilder.AUTO_BUILD -> "AUTO";
			case IncrementalProjectBuilder.CLEAN_BUILD -> "CLEAN";
			default -> "KIND=" + kind;
		};
	}

	/**
	 * Builders that short-circuit via BuildManager.needsBuild still fire a
	 * PRE/POST_PROJECT_BUILD pair. Filter them out by a small duration threshold
	 * so the view only shows builders that actually did work. 100 microseconds is
	 * well above the no-op overhead (which is typically a few microseconds) and
	 * far below any meaningful build step.
	 */
	private static final long SKIPPED_BUILDER_THRESHOLD_NANOS = 100_000L;

	private static final class BuildMonitorContentProvider implements ITreeContentProvider {
		@Override
		public Object[] getElements(Object input) {
			if (input instanceof BuildRecorder r) {
				List<Session> sessions = r.getSessions();
				// newest first
				Object[] arr = sessions.toArray();
				for (int i = 0, j = arr.length - 1; i < j; i++, j--) {
					Object tmp = arr[i];
					arr[i] = arr[j];
					arr[j] = tmp;
				}
				return arr;
			}
			return new Object[0];
		}

		@Override
		public Object[] getChildren(Object parent) {
			if (parent instanceof Session s) {
				return s.getProjects().stream()
						.filter(BuildMonitorView::projectDidWork)
						.toArray();
			}
			if (parent instanceof ProjectEntry p) {
				return p.getBuilders().stream()
						.filter(BuildMonitorView::builderDidWork)
						.toArray();
			}
			return new Object[0];
		}

		@Override
		public Object getParent(Object element) {
			return null;
		}

		@Override
		public boolean hasChildren(Object element) {
			if (element instanceof Session s) {
				return s.getProjects().stream().anyMatch(BuildMonitorView::projectDidWork);
			}
			if (element instanceof ProjectEntry p) {
				return p.getBuilders().stream().anyMatch(BuildMonitorView::builderDidWork);
			}
			return false;
		}
	}
}
