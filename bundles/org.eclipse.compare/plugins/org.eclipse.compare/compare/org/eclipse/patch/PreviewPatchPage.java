/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */
package org.eclipse.patch;

import java.io.*;

import org.eclipse.swt.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.events.*;
import org.eclipse.swt.custom.StyledText;

import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.jface.wizard.IWizard;


/* package */ class PreviewPatchPage extends WizardPage {
	
	private Tree fTree;
	private StyledText fText;
	
	/* package */ PreviewPatchPage() {
		super("Preview Patch", "Preview Patch", null);
	}
	
	/* (non-Javadoc)
	 * Method declared in WizardPage
	 */
	public void setVisible(boolean visible) {
		if (visible)
			update();
		super.setVisible(visible);
	}

	/**
	 * Updates tree and text controls from
	 * list of Diffs.
	 */
	private void update() {
		if (fTree != null && !fTree.isDisposed()) {
			fTree.removeAll();
			fText.setText("");
			
			Diff[] diffs= null;
			IWizard w= getWizard();
			if (w instanceof PatchWizard) {
				PatchWizard pw= (PatchWizard) w;
				diffs= pw.getDiffs();
			}
			
			if (diffs != null) {
				for (int i= 0; i < diffs.length; i++) {
					Diff diff= diffs[i];
					TreeItem d= new TreeItem(fTree, SWT.NULL);
					d.setText(diff.getDescription());
					java.util.List hunks= diff.fHunks;
					java.util.Iterator iter= hunks.iterator();
					while (iter.hasNext()) {
						Hunk hunk= (Hunk) iter.next();
						TreeItem h= new TreeItem(d, SWT.NULL);
						h.setData(hunk);
						h.setText(hunk.getDescription());
					}
				}
			}
		}
	}
	
	public void createControl(Composite parent) {
				
		Composite composite= new Composite(parent, SWT.NULL);
		composite.setLayout(new GridLayout());
		composite.setLayoutData(new GridData(GridData.VERTICAL_ALIGN_FILL | GridData.HORIZONTAL_ALIGN_FILL));
		setControl(composite);
		
		fTree= new Tree(composite, SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL);
		
		GridData data= new GridData();
		data.verticalAlignment= GridData.FILL;
		data.horizontalAlignment= GridData.FILL;
		data.grabExcessHorizontalSpace= true;
		data.grabExcessVerticalSpace= true;
		fTree.setLayoutData(data);
		
		fText= new StyledText(composite, SWT.BORDER | SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
		fText.setEditable(false);
		GridData data2= new GridData();
		data2.verticalAlignment= GridData.FILL;
		data2.horizontalAlignment= GridData.FILL;
		data2.grabExcessHorizontalSpace= true;
		data2.grabExcessVerticalSpace= true;
		fText.setLayoutData(data2);
		
		update();

		fTree.addSelectionListener(
			new SelectionAdapter() {
				public void widgetSelected(SelectionEvent e) {
					Object data= e.item.getData();
					String s= "";
					if (data instanceof Hunk) {
						Hunk hunk= (Hunk) data;
						s= hunk.getContent();
					}
					fText.setText(s);
				} 
			}
		);
		
		// WorkbenchHelp.setHelp(composite, new DialogPageContextComputer(this, PATCH_HELP_CONTEXT_ID));								
	}
}

