/******************************************************************************* * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.debug.internal.ui.views.launch;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.internal.ui.views.DebugUIViewsMessages;import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.ui.IWorkbenchPartSite;
import org.eclipse.ui.internal.progress.PendingUpdateAdapter;
import org.eclipse.ui.progress.DeferredTreeContentManager;
import org.eclipse.ui.progress.IDeferredWorkbenchAdapter;
import org.eclipse.ui.progress.IElementCollector;
import org.eclipse.ui.progress.WorkbenchJob;
/**
 * TODO:
 */
public class IncrementalDeferredTreeContentManager extends DeferredTreeContentManager {

    private LaunchViewer fViewer;
    /**
     * Contructs a new content manager.
     * 
     * @param provider content provider
     * @param viewer viewer
     * @param site part site
     */
    public IncrementalDeferredTreeContentManager(ITreeContentProvider provider, LaunchViewer viewer, IWorkbenchPartSite site) {
        super(provider, viewer, site);
        fViewer = viewer;
    }
    
    /**
     * Create the element collector for the receiver.
     *@param parent
     *            The parent object being filled in,
     * @param placeholder
     *            The adapter that will be used to indicate that results are
     *            pending.
     * @return IElementCollector
     */
    protected IElementCollector createElementCollector(final Object parent, final PendingUpdateAdapter placeholder) {
        return new IElementCollector() {            
            // number of children added to the tree
            int offset = 0;
            
            /*
             *  (non-Javadoc)
             * @see org.eclipse.jface.progress.IElementCollector#add(java.lang.Object, org.eclipse.core.runtime.IProgressMonitor)
             */
            public void add(Object element, IProgressMonitor monitor) {
                add(new Object[] { element }, monitor);
            }

            /*
             *  (non-Javadoc)
             * @see org.eclipse.jface.progress.IElementCollector#add(java.lang.Object[], org.eclipse.core.runtime.IProgressMonitor)
             */
            public void add(Object[] elements, IProgressMonitor monitor) {
                Object[] filtered = fViewer.filter(elements);
                replaceChildren(parent, filtered, offset, monitor);
                offset = offset + filtered.length;
            }

            /*
             * (non-Javadoc)
             * 
             * @see org.eclipse.jface.progress.IElementCollector#done()
             */
            public void done() {
                prune(parent, offset);
            }
        };
    }
    
    /**
     * Returns the child elements of the given element, or in the case of a
     * deferred element, returns a placeholder. If a deferred element is used, a
     * job is created to fetch the children in the background.
     * 
     * @param parent
     *            The parent object.
     * @return Object[] or <code>null</code> if parent is not an instance of
     *         IDeferredWorkbenchAdapter.
     */
    public Object[] getChildren(final Object parent) {
        IDeferredWorkbenchAdapter element = getAdapter(parent);
        if (element == null)
            return null;
        Object[] currentChildren = fViewer.getCurrentChildren(parent);
        PendingUpdateAdapter placeholder = null;
        if (currentChildren == null || currentChildren.length == 0) {
            placeholder = new PendingUpdateAdapter();
        }
        startFetchingDeferredChildren(parent, element, placeholder);
        if (placeholder == null) {
            return currentChildren;
        }
        return new Object[] { placeholder };
    }
    
    /**
     * Create a UIJob to replace the children of the parent in the tree viewer.
     * 
     * @param parent
     * @param children
     * @param offset
     * @param monitor
     */
    protected void replaceChildren(final Object parent, final Object[] children, final int offset, IProgressMonitor monitor) {
        WorkbenchJob updateJob = new WorkbenchJob(DebugUIViewsMessages.getString("IncrementalDeferredTreeContentManager.0")) { //$NON-NLS-1$
            /*
             * (non-Javadoc)
             * 
             * @see org.eclipse.ui.progress.UIJob#runInUIThread(org.eclipse.core.runtime.IProgressMonitor)
             */
            public IStatus runInUIThread(IProgressMonitor updateMonitor) {
                //Cancel the job if the tree viewer got closed
                if (fViewer.getControl().isDisposed())
                    return Status.CANCEL_STATUS;
                //Prevent extra redraws on deletion and addition
                fViewer.getControl().setRedraw(false);
                fViewer.replace(parent, children, offset);
                fViewer.getControl().setRedraw(true);
                return Status.OK_STATUS;
            }
        };
        updateJob.setSystem(true);
        updateJob.schedule();
    } 
    
    /**
     * Create a UIJob to replace the children of the parent in the tree viewer.
     * 
     * @param parent
     * @param children
     * @param offset
     * @param monitor
     */
    protected void prune(final Object parent, final int offset) {
        WorkbenchJob updateJob = new WorkbenchJob(DebugUIViewsMessages.getString("IncrementalDeferredTreeContentManager.1")) { //$NON-NLS-1$
            /*
             * (non-Javadoc)
             * 
             * @see org.eclipse.ui.progress.UIJob#runInUIThread(org.eclipse.core.runtime.IProgressMonitor)
             */
            public IStatus runInUIThread(IProgressMonitor updateMonitor) {
                //Cancel the job if the tree viewer got closed
                if (fViewer.getControl().isDisposed())
                    return Status.CANCEL_STATUS;
                //Prevent extra redraws on deletion and addition
                fViewer.getControl().setRedraw(false);
                fViewer.prune(parent, offset);
                fViewer.getControl().setRedraw(true);
                return Status.OK_STATUS;
            }
        };
        updateJob.setSystem(true);
        updateJob.schedule();
    }         	/* (non-Javadoc)	 * @see org.eclipse.ui.progress.DeferredTreeContentManager#runClearPlaceholderJob(org.eclipse.ui.internal.progress.PendingUpdateAdapter)	 */	protected void runClearPlaceholderJob(PendingUpdateAdapter placeholder) {		if (placeholder != null) {			super.runClearPlaceholderJob(placeholder);		}	}}
