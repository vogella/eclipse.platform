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

import org.eclipse.swt.SWT;

/**
 * Encodes mouse events as xterm mouse-reporting input sequences.
 * <p>
 * Supports the X11 reporting modes (DEC private modes 1000, 1002 and 1003) with
 * either the classic encoding or the SGR encoding (DEC private mode 1006).
 * <p>
 * The reporting modes only differ in which events are forwarded:
 * <ul>
 * <li>{@link Mode#NORMAL} (1000): button presses, releases and the mouse wheel.</li>
 * <li>{@link Mode#BUTTON_EVENT} (1002): like {@code NORMAL} plus motion while a
 * button is held down.</li>
 * <li>{@link Mode#ANY_EVENT} (1003): like {@code BUTTON_EVENT} plus motion without
 * any button held.</li>
 * </ul>
 * The class only deals with the protocol; obtaining the cell coordinates and the
 * pressed button is the responsibility of the caller.
 */
public final class MouseReporting {

	/** The X11 mouse reporting mode. */
	public enum Mode {
		/** Mouse reporting is off. */
		NONE,
		/** DEC private mode 1000: report button press and release. */
		NORMAL,
		/** DEC private mode 1002: additionally report motion while a button is held. */
		BUTTON_EVENT,
		/** DEC private mode 1003: additionally report motion without a button held. */
		ANY_EVENT;
	}

	/** The kind of mouse event being reported. */
	public enum EventType {
		PRESS, RELEASE, MOVE, WHEEL_UP, WHEEL_DOWN;
	}

	// Low button bits as defined by the xterm protocol.
	private static final int BUTTON_NONE = 3;
	private static final int WHEEL_UP_BUTTON = 64;
	private static final int WHEEL_DOWN_BUTTON = 65;
	private static final int MOTION_FLAG = 32;
	// The classic encoding offsets every value by 32 and cannot represent values above 223.
	private static final int CLASSIC_OFFSET = 32;
	private static final int CLASSIC_MAX = 223;

	private MouseReporting() {
	}

	/**
	 * Encodes a mouse event for the given reporting mode.
	 *
	 * @param mode the active reporting mode.
	 * @param sgr <code>true</code> to use the SGR encoding (DEC private mode 1006).
	 * @param type the kind of event.
	 * @param swtButton the SWT mouse button (1 = left, 2 = middle, 3 = right) for press
	 *            and release events; ignored for wheel events.
	 * @param column the zero-based column of the event, relative to the visible screen.
	 * @param line the zero-based line of the event, relative to the visible screen.
	 * @param stateMask the SWT state mask holding the active keyboard modifiers.
	 * @param buttonHeld whether a mouse button is currently held down (only relevant for
	 *            {@link EventType#MOVE}).
	 * @return the bytes to send to the connected process, or <code>null</code> if this
	 *         event must not be reported in the given mode.
	 */
	public static String encode(Mode mode, boolean sgr, EventType type, int swtButton, int column, int line,
			int stateMask, boolean buttonHeld) {
		if (mode == Mode.NONE || column < 0 || line < 0) {
			return null;
		}

		boolean release = false;
		int button;
		switch (type) {
		case PRESS:
			button = buttonBits(swtButton);
			break;
		case RELEASE:
			button = buttonBits(swtButton);
			release = true;
			break;
		case WHEEL_UP:
			button = WHEEL_UP_BUTTON;
			break;
		case WHEEL_DOWN:
			button = WHEEL_DOWN_BUTTON;
			break;
		case MOVE:
			if (mode == Mode.NORMAL || (mode == Mode.BUTTON_EVENT && !buttonHeld)) {
				return null; // motion is not reported in this mode
			}
			button = buttonHeld ? buttonBits(swtButton) : BUTTON_NONE;
			break;
		default:
			return null;
		}
		if (button < 0) {
			return null; // unsupported button
		}

		int modifiers = modifierBits(stateMask);
		int motion = type == EventType.MOVE ? MOTION_FLAG : 0;

		if (sgr) {
			int cb = button + motion + modifiers;
			return "\u001b[<" + cb + ';' + (column + 1) + ';' + (line + 1) + (release ? 'm' : 'M'); //$NON-NLS-1$
		}

		// Classic encoding: a release does not carry the button, it is reported as BUTTON_NONE.
		int cb = (release ? BUTTON_NONE : button) + motion + modifiers;
		int cx = Math.min(column + 1, CLASSIC_MAX);
		int cy = Math.min(line + 1, CLASSIC_MAX);
		return "\u001b[M" + (char) (cb + CLASSIC_OFFSET) + (char) (cx + CLASSIC_OFFSET) //$NON-NLS-1$
				+ (char) (cy + CLASSIC_OFFSET);
	}

	private static int buttonBits(int swtButton) {
		switch (swtButton) {
		case 1:
			return 0; // left
		case 2:
			return 1; // middle
		case 3:
			return 2; // right
		default:
			return -1;
		}
	}

	private static int modifierBits(int stateMask) {
		int modifiers = 0;
		if ((stateMask & SWT.SHIFT) != 0) {
			modifiers += 4;
		}
		if ((stateMask & SWT.ALT) != 0) {
			modifiers += 8;
		}
		if ((stateMask & SWT.CTRL) != 0) {
			modifiers += 16;
		}
		return modifiers;
	}
}
