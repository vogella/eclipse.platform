/*******************************************************************************
 * Copyright (c) 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.ant.internal.ui.debug;

import org.eclipse.debug.core.model.IBreakpoint;

public interface IAntDebugController {
	
    /**
     * Resume the Ant build
     */
	public void resume();
    
    /**
     * Suspend the Ant build
     */
	public void suspend();
    
     /**
     * Step into the current Ant task
     */
	public void stepInto();
    
     /**
     * Step over the current Ant task
     */
	public void stepOver();
    
    /**
     * The provided breakpoint has been added or removed depending on the <code>added</code> parameter.
     * Updates the controller for this change.
     * 
     * @param breakpoint the breakpoint that has been added or removed
     * @param added whether or not the breakpoint has been added 
     */
	public void handleBreakpoint(IBreakpoint breakpoint, boolean added);
    
     /**
     * Retrieve the properties of the Ant build.
     * May occur asynchronously depending on implementation.
     */
	public void getProperties();
    
    /**
     * Retrieve the stack frames of the Ant build.
     * May occur asynchronously depending on implementation.
     */
	public void getStackFrames();
}
