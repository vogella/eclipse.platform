/*******************************************************************************
 * Copyright (c) 2026 Eclipse contributors and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.terminal.internal.emulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.eclipse.swt.SWT;
import org.eclipse.terminal.internal.emulator.MouseReporting.EventType;
import org.eclipse.terminal.internal.emulator.MouseReporting.Mode;
import org.junit.jupiter.api.Test;

public class MouseReportingTest {

	/** The escape character that starts every reported sequence. */
	private static final String ESC = String.valueOf((char) 27);

	private static String encodeSgr(Mode mode, EventType type, int button, int column, int line, int stateMask,
			boolean held) {
		return MouseReporting.encode(mode, true, type, button, column, line, stateMask, held);
	}

	private static String encodeClassic(Mode mode, EventType type, int button, int column, int line, int stateMask,
			boolean held) {
		return MouseReporting.encode(mode, false, type, button, column, line, stateMask, held);
	}

	@Test
	public void modeNoneIsNeverReported() {
		assertNull(encodeSgr(Mode.NONE, EventType.PRESS, 1, 0, 0, 0, false));
		assertNull(encodeClassic(Mode.NONE, EventType.PRESS, 1, 0, 0, 0, false));
	}

	@Test
	public void negativeCoordinatesAreNotReported() {
		assertNull(encodeSgr(Mode.NORMAL, EventType.PRESS, 1, -1, 0, 0, false));
		assertNull(encodeSgr(Mode.NORMAL, EventType.PRESS, 1, 0, -1, 0, false));
	}

	@Test
	public void sgrLeftButtonPressAndRelease() {
		// left button at column 4, line 2 -> coordinates are reported 1-based
		assertEquals(ESC + "[<0;5;3M", encodeSgr(Mode.NORMAL, EventType.PRESS, 1, 4, 2, 0, false));
		// the release carries the same button but ends with a lowercase 'm'
		assertEquals(ESC + "[<0;5;3m", encodeSgr(Mode.NORMAL, EventType.RELEASE, 1, 4, 2, 0, false));
	}

	@Test
	public void sgrMiddleAndRightButton() {
		assertEquals(ESC + "[<1;1;1M", encodeSgr(Mode.NORMAL, EventType.PRESS, 2, 0, 0, 0, false));
		assertEquals(ESC + "[<2;1;1M", encodeSgr(Mode.NORMAL, EventType.PRESS, 3, 0, 0, 0, false));
	}

	@Test
	public void unsupportedButtonIsNotReported() {
		assertNull(encodeSgr(Mode.NORMAL, EventType.PRESS, 0, 0, 0, 0, false));
	}

	@Test
	public void modifiersAreAddedToTheButtonCode() {
		assertEquals(ESC + "[<4;1;1M", encodeSgr(Mode.NORMAL, EventType.PRESS, 1, 0, 0, SWT.SHIFT, false));
		assertEquals(ESC + "[<8;1;1M", encodeSgr(Mode.NORMAL, EventType.PRESS, 1, 0, 0, SWT.ALT, false));
		assertEquals(ESC + "[<16;1;1M", encodeSgr(Mode.NORMAL, EventType.PRESS, 1, 0, 0, SWT.CTRL, false));
	}

	@Test
	public void wheelIsReportedInAllModes() {
		assertEquals(ESC + "[<64;1;1M", encodeSgr(Mode.NORMAL, EventType.WHEEL_UP, 0, 0, 0, 0, false));
		assertEquals(ESC + "[<65;1;1M", encodeSgr(Mode.NORMAL, EventType.WHEEL_DOWN, 0, 0, 0, 0, false));
		assertEquals(ESC + "[<64;1;1M", encodeSgr(Mode.ANY_EVENT, EventType.WHEEL_UP, 0, 0, 0, 0, false));
	}

	@Test
	public void motionIsOnlyReportedInTheRelevantModes() {
		// NORMAL never reports motion
		assertNull(encodeSgr(Mode.NORMAL, EventType.MOVE, 1, 0, 0, 0, true));
		// BUTTON_EVENT reports motion only while a button is held
		assertNull(encodeSgr(Mode.BUTTON_EVENT, EventType.MOVE, 1, 0, 0, 0, false));
		assertEquals(ESC + "[<32;3;2M", encodeSgr(Mode.BUTTON_EVENT, EventType.MOVE, 1, 2, 1, 0, true));
		// ANY_EVENT reports motion without a button held; the button defaults to "none" (3)
		assertEquals(ESC + "[<35;3;2M", encodeSgr(Mode.ANY_EVENT, EventType.MOVE, 0, 2, 1, 0, false));
	}

	@Test
	public void classicEncodingOffsetsEveryValueBy32() {
		// column 0, line 0 -> 1-based coordinates 1,1 each offset by 32 == '!'
		assertEquals(ESC + "[M !!", encodeClassic(Mode.NORMAL, EventType.PRESS, 1, 0, 0, 0, false));
	}

	@Test
	public void classicReleaseUsesTheReleaseButtonCode() {
		// a classic release cannot carry the button, it is reported as button 3 -> 3 + 32 == '#'
		assertEquals(ESC + "[M#!!", encodeClassic(Mode.NORMAL, EventType.RELEASE, 1, 0, 0, 0, false));
	}

	@Test
	public void classicCoordinatesAreClampedTo223() {
		// 1-based column 301 would overflow the single byte encoding, it is clamped to 223 -> 223 + 32 == 255.
		// Layout of the classic sequence: ESC '[' 'M' Cb Cx Cy, so the column byte is at index 4.
		String encoded = encodeClassic(Mode.NORMAL, EventType.PRESS, 1, 300, 0, 0, false);
		assertEquals((char) 255, encoded.charAt(4));
	}
}
