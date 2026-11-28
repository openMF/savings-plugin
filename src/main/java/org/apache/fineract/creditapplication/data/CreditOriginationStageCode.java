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
 * Stable, ordered credit-origination phase identifiers. Phases with {@code eventsOnly} have no
 * Fineract fallback and only progress through published stage events.
 */
@Getter
@RequiredArgsConstructor
public enum CreditOriginationStageCode {
  ONBOARDING("Commercial registration or onboarding", false),
  COMPLIANCE("File / Compliance", false),
  PARAMETRIC_SCORE("Parametric Score / Credit", true),
  FILE_INTEGRATION("Integration of complete file", true),
  CREDIT_ANALYSIS("Credit analysis", true),
  APPROVAL("Approval / Committee", false),
  LEGAL_INSTRUMENTATION("Legal Instrumentation and Validation", true),
  DISBURSEMENT("Administration / Dispersal / Committee", false),
  RECOVERY("Recovery / Collection", false);

  private final String displayName;
  private final boolean eventsOnly;
}
