/*******************************************************************************
 * Copyright (c) 2000, 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.debug.internal.ui.actions;


import java.util.Iterator;

import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.internal.ui.DebugUIPlugin;
import org.eclipse.debug.internal.ui.DelegatingModelPresentation;
import org.eclipse.debug.internal.ui.IDebugHelpContextIds;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.actions.SelectionProviderAction;
import org.eclipse.ui.help.WorkbenchHelp;

public class OpenBreakpointMarkerAction extends SelectionProviderAction {

	protected static DelegatingModelPresentation fgPresentation = new DelegatingModelPresentation();
	
	public OpenBreakpointMarkerAction(ISelectionProvider selectionProvider) {
		super(selectionProvider, ActionMessages.getString("OpenBreakpointMarkerAction.&Go_to_File_1")); //$NON-NLS-1$
		setToolTipText(ActionMessages.getString("OpenBreakpointMarkerAction.Go_to_File_for_Breakpoint_2")); //$NON-NLS-1$
		ISharedImages images= DebugUIPlugin.getDefault().getWorkbench().getSharedImages();
		setImageDescriptor(images.getImageDescriptor(ISharedImages.IMG_OPEN_MARKER));
		WorkbenchHelp.setHelp(
			this,
			IDebugHelpContextIds.OPEN_BREAKPOINT_ACTION);
		setEnabled(false);
	}

	/**
	 * @see IAction#run()
	 */
	public void run() {
		IWorkbenchWindow dwindow= DebugUIPlugin.getActiveWorkbenchWindow();
		if (dwindow == null) {
			return;
		}
		IWorkbenchPage page= dwindow.getActivePage();
		if (page == null) {
			return;
		}
		
		IStructuredSelection selection= getStructuredSelection();
		if (selection.isEmpty()) {
			setEnabled(false);
			return;
		}
		Iterator itr= selection.iterator();
		IBreakpoint breakpoint= (IBreakpoint)itr.next();
		IEditorInput input= fgPresentation.getEditorInput(breakpoint);
		IEditorPart part= null;
		if (input != null) {
			String editorId = fgPresentation.getEditorId(input, breakpoint);
			try {
				part= page.openEditor(input, editorId);
			} catch (PartInitException e) {
				DebugUIPlugin.errorDialog(dwindow.getShell(), ActionMessages.getString("OpenBreakpointMarkerAction.Go_to_Breakpoint_1"), ActionMessages.getString("OpenBreakpointMarkerAction.Exceptions_occurred_attempting_to_open_the_editor_for_the_breakpoint_resource_2"), e); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		if (part != null) {
			part.setFocus();
			part.gotoMarker(breakpoint.getMarker());
		}
	}
	
	/**
	 * @see SelectionProviderAction#selectionChanged(ISelection)
	 */
	public void selectionChanged(IStructuredSelection sel) {
		if (sel.size() == 1) {
			setEnabled(true);
		} else {
			setEnabled(false);
		}
	}
}
