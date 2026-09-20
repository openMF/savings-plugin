/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.data;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Stable, ordered credit-origination phase identifiers. */
@Getter
@RequiredArgsConstructor
public enum CreditOriginationStageCode {
  ONBOARDING("Commercial registration or onboarding"),
  COMPLIANCE("File / Compliance"),
  PARAMETRIC_SCORE("Parametric Score / Credit"),
  FILE_INTEGRATION("Integration of complete file"),
  CREDIT_ANALYSIS("Credit analysis"),
  APPROVAL("Approval / Committee"),
  LEGAL_INSTRUMENTATION("Legal Instrumentation and Validation"),
  DISBURSEMENT("Administration / Dispersal / Committee"),
  RECOVERY("Recovery / Collection");

  private final String displayName;
}
