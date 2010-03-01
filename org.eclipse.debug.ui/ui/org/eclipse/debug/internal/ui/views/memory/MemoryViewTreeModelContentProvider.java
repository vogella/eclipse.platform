/*******************************************************************************
 * Copyright (c) 2006, 2010 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.debug.internal.ui.views.memory;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.model.IMemoryBlock;
import org.eclipse.debug.core.model.IMemoryBlockRetrieval;
import org.eclipse.debug.internal.ui.viewers.model.TreeModelContentProvider;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IModelDelta;
import org.eclipse.debug.internal.ui.viewers.model.provisional.TreeModelViewer;

public class MemoryViewTreeModelContentProvider extends
		TreeModelContentProvider {
	
	protected void updateNodes(IModelDelta[] nodes) {
		
		if (getViewer() instanceof TreeModelViewer)
		{
	        for (int i = 0; i < nodes.length; i++) {
				IModelDelta node = nodes[i];
				int flags = node.getFlags();

				if ((flags & IModelDelta.ADDED) != 0) {
					if (node.getElement() instanceof IMemoryBlock) {
						if ((flags & IModelDelta.SELECT) != 0) {
							if (getPresentationContext() instanceof MemoryView) {
								MemoryView view = (MemoryView) getPresentationContext().getPart();
								if (view.isPinMBDisplay()) {
									// turn off select if the view is currently
									// pinned
									flags |= IModelDelta.SELECT;
									flags ^= IModelDelta.SELECT;
								}
							}
						}

						// override and select the first memory block
						if (isFirstMemoryBlock()) {
							flags |= IModelDelta.SELECT;
						}
					}
				}
				if ((flags & IModelDelta.ADDED) != 0) {
					handleAdd(node);
				}
				if ((flags & IModelDelta.REMOVED) != 0) {
					handleRemove(node);
				}
				if ((flags & IModelDelta.CONTENT) != 0) {
					handleContent(node);
				}
				if ((flags & IModelDelta.EXPAND) != 0) {
					handleExpand(node);
				}
				if ((flags & IModelDelta.SELECT) != 0) {
					handleSelect(node);
				}
				if ((flags & IModelDelta.STATE) != 0) {
					handleState(node);
				}
				if ((flags & IModelDelta.INSERTED) != 0) {
					handleInsert(node);
				}
				if ((flags & IModelDelta.REPLACED) != 0) {
					handleReplace(node);
				}
				if ((flags & IModelDelta.INSTALL) != 0) {
					handleInstall(node);
				}
				if ((flags & IModelDelta.UNINSTALL) != 0) {
					handleUninstall(node);
				}
				updateNodes(node.getChildDeltas());
	        }
		}
	}
	
	private boolean isFirstMemoryBlock()
	{
		Object input = getViewer().getInput();
		if (input instanceof IMemoryBlockRetrieval)
		{
			IMemoryBlock[] memoryBlocks = DebugPlugin.getDefault().getMemoryBlockManager().getMemoryBlocks((IMemoryBlockRetrieval)input);
			if (memoryBlocks.length == 1)
				return true;
		}
		return false;
	}
}
