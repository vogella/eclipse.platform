package org.eclipse.update.internal.ui.manager;

import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.part.*;
import org.eclipse.swt.SWT;
import org.eclipse.update.internal.ui.parts.*;
import org.eclipse.jface.viewers.*;
import org.eclipse.ui.*;
import org.eclipse.update.core.*;
import org.eclipse.update.ui.internal.model.*;
import org.eclipse.jface.action.*;
import org.eclipse.update.internal.ui.*;
import org.eclipse.swt.program.Program;
import org.eclipse.update.internal.transform.*;
import org.eclipse.ui.texteditor.IUpdate;

/**
 * Insert the type's description here.
 * @see ViewPart
 */
public class DetailsView extends MultiPageView {
public static final String HOME_PAGE = "Home";
public static final String SITE_PAGE = "Site";
public static final String DETAILS_PAGE = "Details";
public static final String BROWSER_PAGE = "Browser";
public static final String HOME_URL = "update://index.html";

private Action homeAction;
private UpdateAction backAction;
private UpdateAction forwardAction;
private DetailsHistory history = new DetailsHistory();

private boolean inHistory=false;

abstract class UpdateAction extends Action implements IUpdate {
}

	/**
	 * The constructor.
	 */
	public DetailsView() {
		history = new DetailsHistory();
	}
	
public void createPages() {
	firstPageId = HOME_PAGE;
	formWorkbook.setFirstPageSelected(false);
	MainPage mainPage =
		new MainPage(this, "Update Home");
	addPage(HOME_PAGE, mainPage);
	DetailsPage detailsPage = 
		new DetailsPage(this, "Details");
	addPage(DETAILS_PAGE, detailsPage);
	SitePage sitePage = 
		new SitePage(this, "Site");
	addPage(SITE_PAGE, sitePage);
	if (SWT.getPlatform().equals("win32")) {
		addWebBrowser();
	}
}

private void addWebBrowser() {
	EmbeddedBrowser browser = new EmbeddedBrowser(this);
	browser.setBrowserListener(new IBrowserListener () {
		public void downloadComplete(String url) {
			System.out.println("Complete: inHistory="+inHistory+", url="+url);
			if (inHistory)
				inHistory = false;
			else
		   		history.add(BROWSER_PAGE, url);
		   	backAction.update();
		   	forwardAction.update();
		}
	});
   	addPage(BROWSER_PAGE, browser);
}
	
public void showURL(String url) {
	if (SWT.getPlatform().equals("win32")) {
		showPage(BROWSER_PAGE, url);
	}
	else {
		Program.launch(url);
	}
}

public void createPartControl(Composite parent) {
	super.createPartControl(parent);
	history.add(HOME_PAGE, null);
	makeActions();
	fillActionBars();
}

private void showPageWithInput(String pageId, Object input) {
	showPage(pageId, input);
	history.add(pageId, input);
   	backAction.update();
   	forwardAction.update();
}
	
public void selectionChanged(IWorkbenchPart part, ISelection sel) {
	if (part == this) return;
	if (sel instanceof IStructuredSelection) {
		IStructuredSelection ssel = (IStructuredSelection)sel;
		if (ssel.size()==1) {
			Object el = ssel.getFirstElement();
			if (el instanceof IFeature || el instanceof ChecklistJob) {
				showPageWithInput(DETAILS_PAGE, el);
				return;
			}
			if (el instanceof SiteBookmark) {
				showPageWithInput(SITE_PAGE, el);
			}
		}
	}
}

private void makeActions() {
	homeAction = new Action () {
		public void run() {
			performHome();
		}
	};
	homeAction.setToolTipText("Go to the Update Manager home page");
	homeAction.setImageDescriptor(UpdateUIPluginImages.DESC_HOME_NAV);
	homeAction.setHoverImageDescriptor(UpdateUIPluginImages.DESC_HOME_NAV_H);
	homeAction.setDisabledImageDescriptor(UpdateUIPluginImages.DESC_HOME_NAV_D);
	
	backAction = new UpdateAction () {
		public void run() {
			performBackward();
		}
		public void update() {
			setEnabled(canPerformBackward());
		}
	};
	backAction.setToolTipText("Go Back");
	backAction.setImageDescriptor(UpdateUIPluginImages.DESC_BACKWARD_NAV);
	backAction.setHoverImageDescriptor(UpdateUIPluginImages.DESC_BACKWARD_NAV_H);
	backAction.setDisabledImageDescriptor(UpdateUIPluginImages.DESC_BACKWARD_NAV_D);
	backAction.setEnabled(false);
	
	forwardAction = new UpdateAction () {
		public void run() {
			performForward();
		}
		public void update() {
			setEnabled(canPerformForward());
		}
	};
	forwardAction.setToolTipText("Go Forward");
	forwardAction.setImageDescriptor(UpdateUIPluginImages.DESC_FORWARD_NAV);
	forwardAction.setHoverImageDescriptor(UpdateUIPluginImages.DESC_FORWARD_NAV_H);
	forwardAction.setDisabledImageDescriptor(UpdateUIPluginImages.DESC_FORWARD_NAV_D);
	forwardAction.setEnabled(false);
}

private void fillActionBars() {
	IActionBars bars = getViewSite().getActionBars();
	IToolBarManager mng = bars.getToolBarManager();
	mng.add(homeAction);
	mng.add(backAction);
	mng.add(forwardAction);
}

private void performHome() {
   	showPage(HOME_PAGE);
}

private boolean canPerformBackward() {
	return history.hasPrevious();
}
	

private void performBackward() {
	DetailsHistoryItem item = history.getPrevious();
	if (item!=null) {
		if (item.getPageId() == BROWSER_PAGE)
		   inHistory=true;
	   	showPage(item.getPageId(), item.getInput());
		if (item.getPageId() != BROWSER_PAGE) {
		   	backAction.update();
		   	forwardAction.update();
		}
	}
}

private void performForward() {
	DetailsHistoryItem item = history.getNext();
	if (item!=null) {
		if (item.getPageId() == BROWSER_PAGE)
		   inHistory=true;
		showPage(item.getPageId(), item.getInput());
		if (item.getPageId() != BROWSER_PAGE) {
		   	backAction.update();
		   	forwardAction.update();
		}
	}
}

private boolean canPerformForward() {
	return history.hasNext();
}


}