/*******************************************************************************
 * Copyright (c) 2004, 2018 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.core.tests.runtime.perf;

import org.eclipse.core.tests.harness.session.PerformanceSessionTest;
import org.eclipse.core.tests.harness.session.SessionTestExtension;
import org.eclipse.core.tests.runtime.RuntimeTestsPlugin;
import org.eclipse.test.performance.Dimension;
import org.eclipse.test.performance.Performance;
import org.eclipse.test.performance.PerformanceMeter;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class UIStartupTest {

	@RegisterExtension
	static final SessionTestExtension sessionTestExtension = SessionTestExtension
			.forPlugin(RuntimeTestsPlugin.PI_RUNTIME_TESTS)
				.withApplicationId(SessionTestExtension.UI_TEST_APPLICATION).create();

	@Test
	@Order(0)
	public void warmup() {
		testUIApplicationStartup(true);
	}

	@PerformanceSessionTest(repetitions = 5)
	@Order(1)
	public void runMeasurements() {
		testUIApplicationStartup(false);
	}

	public void testUIApplicationStartup(boolean warmup) {
		PerformanceMeter meter = Performance.getDefault()
				.createPerformanceMeter(getClass().getName() + '.' + UIStartupTest.class.getName());
		try {
			meter.stop();
			Performance performance = Performance.getDefault();
			performance.tagAsGlobalSummary(meter, "Core UI Startup", Dimension.ELAPSED_PROCESS);
			if (!warmup) {
				meter.commit();
			}
			performance.assertPerformanceInRelativeBand(meter, Dimension.ELAPSED_PROCESS, -50, 5);
		} finally {
			meter.dispose();
		}
	}
}
