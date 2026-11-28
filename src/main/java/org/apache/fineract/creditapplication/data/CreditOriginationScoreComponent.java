/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.data;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Parametric-score evidence components. SAT, Credit Bureau and Syntage are mandatory before the
 * PARAMETRIC_SCORE stage may complete; RUG and RPC are informational and never block progression.
 */
@Getter
@RequiredArgsConstructor
public enum CreditOriginationScoreComponent {
  SAT("SAT - Tax Authority", true),
  CREDIT_BUREAU("Credit Bureau (Buro)", true),
  SYNTAGE("Syntage Score", true),
  RUG("RUG - Movable Collateral Registry", false),
  RPC("RPC - Public Commercial Registry", false);

  private final String displayName;
  private final boolean required;
}
