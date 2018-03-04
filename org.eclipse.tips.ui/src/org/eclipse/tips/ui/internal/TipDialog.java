/*******************************************************************************
 * Copyright (c) 2018 Remain Software
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     wim.jongman@remainsoftware.com - initial API and implementation
 *******************************************************************************/
package org.eclipse.tips.ui.internal;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.tips.core.TipManager;

/**
 * The dialog containing the tips.
 *
 */
public class TipDialog extends Dialog {

	/**
	 * When passed as style, the default style will be used which is
	 * <p>
	 * (SWT.RESIZE | SWT.SHELL_TRIM)
	 */
	public static final int DEFAULT_STYLE = -1;
	private TipManager fTipManager;
	private TipComposite fTipComposite;
	private int fShellStyle;

	public TipDialog(Shell parentShell, TipManager tipManager, int shellStyle) {
		super(parentShell);
		fTipManager = tipManager;
		fShellStyle = (shellStyle == DEFAULT_STYLE) ? (SWT.RESIZE | SWT.SHELL_TRIM) : shellStyle;
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		fixLayout(parent);
		Composite area = (Composite) super.createDialogArea(parent);
		fixLayout(area);
		fTipComposite = new TipComposite(area, SWT.NONE);
		fixLayout(fTipComposite);
		getShell().setLocation(getShell().getMonitor().getClientArea().width / 2 - parent.getSize().x / 2,
				getShell().getMonitor().getClientArea().height / 2 - parent.getSize().y / 2);
		getShell().setText("Tip of the Day");
		fTipComposite.addDisposeListener(event -> close());
		return area;
	}

	@Override
	protected void createButtonsForButtonBar(Composite pParent) {
	}

	@Override
	protected Control createButtonBar(Composite pParent) {
		Control bar = super.createButtonBar(pParent);
		fixLayout((Composite) bar);
		return bar;
	}
	
	@Override
	protected int getShellStyle() {
		return fShellStyle;
	}

	private void fixLayout(Composite parent) {
		((GridLayout) parent.getLayout()).marginHeight = 0;
		((GridLayout) parent.getLayout()).marginBottom = 0;
		((GridLayout) parent.getLayout()).marginLeft = 0;
		((GridLayout) parent.getLayout()).marginRight = 0;
		((GridLayout) parent.getLayout()).marginWidth = 0;
		((GridLayout) parent.getLayout()).marginTop = 0;
		GridDataFactory.fillDefaults().grab(true, true).applyTo(parent);
	}

	@Override
	public int open() {
		setBlockOnOpen(false);
		int result = super.open();
		if (result == Window.OK) {
			fTipComposite.setTipManager(fTipManager);
		}
		return result;
	}
}