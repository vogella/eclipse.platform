/*******************************************************************************
 * Copyright (c) 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.core.tests.harness;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import junit.framework.Assert;
import org.eclipse.core.runtime.IRegistryChangeEvent;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.*;
import org.osgi.service.packageadmin.PackageAdmin;

public class BundleTestingHelper {

	public static Bundle[] getBundles(BundleContext context, String symbolicName, String version) {
		ServiceReference packageAdminReference = context.getServiceReference(PackageAdmin.class.getName());
		if (packageAdminReference == null)
			throw new IllegalStateException("No package admin service found");
		PackageAdmin packageAdmin = (PackageAdmin) context.getService(packageAdminReference);
		Bundle[] result = packageAdmin.getBundles(symbolicName, version);
		context.ungetService(packageAdminReference);
		return result;
	}

	/**
	 * @deprecated
	 */
	public static Bundle installBundle(BundleContext context, String location) throws BundleException, MalformedURLException, IOException {
		return installBundle("", context, location);
	}

	public static Bundle installBundle(String tag, BundleContext context, String location) throws BundleException, MalformedURLException, IOException {
		URL entry = context.getBundle().getEntry(location);
		if (entry == null)
			Assert.fail(tag + " entry " + location + " could not be found in " + context.getBundle().getSymbolicName());
		Bundle installed = context.installBundle(Platform.asLocalURL(entry).toExternalForm());
		return installed;
	}

	/**
	 * Do PackageAdmin.refreshPackages() in a synchronous way.  After installing
	 * all the requested bundles we need to do a refresh and want to ensure that 
	 * everything is done before returning.
	 * @param bundles
	 */
	//copied from EclipseStarter
	public static void refreshPackages(BundleContext context, Bundle[] bundles) {
		if (bundles.length == 0)
			return;
		ServiceReference packageAdminRef = context.getServiceReference(PackageAdmin.class.getName());
		PackageAdmin packageAdmin = null;
		if (packageAdminRef != null) {
			packageAdmin = (PackageAdmin) context.getService(packageAdminRef);
			if (packageAdmin == null)
				return;
		}
		// TODO this is such a hack it is silly.  There are still cases for race conditions etc
		// but this should allow for some progress...
		// (patch from John A.)
		final boolean[] flag = new boolean[] {false};
		FrameworkListener listener = new FrameworkListener() {
			public void frameworkEvent(FrameworkEvent event) {
				if (event.getType() == FrameworkEvent.PACKAGES_REFRESHED)
					synchronized (flag) {
						flag[0] = true;
						flag.notifyAll();
					}
			}
		};
		context.addFrameworkListener(listener);
		packageAdmin.refreshPackages(bundles);
		synchronized (flag) {
			while (!flag[0]) {
				try {
					flag.wait();
				} catch (InterruptedException e) {
					// who cares....
				}
			}
		}
		context.removeFrameworkListener(listener);
		context.ungetService(packageAdminRef);
	}
	public static void runWithBundles(String tag, Runnable runnable, BundleContext context, String[] locations, TestRegistryChangeListener listener) {
		if (listener != null)
			listener.register();
		try {
			Bundle[] installed = new Bundle[locations.length];
			for (int i = 0; i < locations.length; i++)
				try {
					installed[i] = installBundle(tag + ".setup.0", context, locations[i]);
					Assert.assertEquals(tag + ".setup.1." + locations[i], Bundle.INSTALLED, installed[i].getState());
				} catch (BundleException e) {
					CoreTest.fail(tag + ".setup.2" + locations[i], e);
				} catch (IOException e) {
					CoreTest.fail(tag + ".setup.3" + locations[i], e);
				}
			if (listener != null)
				listener.reset();
			BundleTestingHelper.refreshPackages(context, installed);
			if (listener != null) {
				IRegistryChangeEvent event = listener.getEvent(installed.length * 10000);
				// ensure the contributions were properly added
				Assert.assertNotNull(tag + ".setup.4", event);
			}
			try {
				runnable.run();
			} finally {
				if (listener != null)
					listener.reset();
				// remove installed bundles
				for (int i = 0; i < installed.length; i++)
					try {
						installed[i].uninstall();
					} catch (BundleException e) {
						CoreTest.fail(tag + ".tearDown.1." + locations[i], e);
					}
				BundleTestingHelper.refreshPackages(context, installed);
				if (listener != null) {
					IRegistryChangeEvent event = listener.getEvent(installed.length * 10000);
					// ensure the contributions were properly added
					Assert.assertNotNull(tag + ".tearDown.2", event);
				}
			}
		} finally {
			if (listener != null)
				listener.unregister();
		}
	}

}
