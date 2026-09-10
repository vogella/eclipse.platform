/*******************************************************************************
 * Copyright (c) 2002, 2013 GEBIT Gesellschaft fuer EDV-Beratung und Informatik-Technologien mbH,
 * Berlin, Duesseldorf, Frankfurt (Germany) and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     GEBIT Gesellschaft fuer EDV-Beratung und Informatik-Technologien mbH - initial API and implementation
 * 	   IBM Corporation - bug 31796, bug 24108, bug 47139
 *******************************************************************************/

package org.eclipse.ant.internal.ui.editor.text;

import org.eclipse.jface.text.rules.IRule;
import org.eclipse.jface.text.rules.MultiLineRule;
import org.eclipse.jface.text.rules.SingleLineRule;
import org.eclipse.jface.text.rules.Token;
import org.eclipse.jface.text.rules.WhitespaceRule;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.ui.editors.text.SyntaxThemeConstants;

/**
 * The scanner to tokenize for strings and tags
 */
public class AntEditorTagScanner extends AbstractAntEditorScanner {

	private final Token fStringToken;

	public AntEditorTagScanner() {
		fStringToken = new Token(createTextAttribute(SyntaxThemeConstants.STRING_COLOR));

		IRule[] rules = new IRule[3];

		// Add rule for single and double quotes
		rules[0] = new MultiLineRule("\"", "\"", fStringToken, '\\'); //$NON-NLS-1$ //$NON-NLS-2$
		rules[1] = new SingleLineRule("'", "'", fStringToken, '\\'); //$NON-NLS-1$ //$NON-NLS-2$

		// Add generic whitespace rule.
		rules[2] = new WhitespaceRule(new AntEditorWhitespaceDetector());

		setRules(rules);

		setDefaultReturnToken(new Token(createTextAttribute(SyntaxThemeConstants.TAG_COLOR)));
	}

	public void adaptToPreferenceChange(PropertyChangeEvent event) {
		// a theme other than the default one prefixes the colour id with its own id
		String property = event.getProperty();
		if (property.endsWith(SyntaxThemeConstants.STRING_COLOR)) {
			adaptToColorChange(fStringToken, SyntaxThemeConstants.STRING_COLOR);
		} else if (property.endsWith(SyntaxThemeConstants.TAG_COLOR)) {
			adaptToColorChange((Token) fDefaultReturnToken, SyntaxThemeConstants.TAG_COLOR);
		}
	}
}
