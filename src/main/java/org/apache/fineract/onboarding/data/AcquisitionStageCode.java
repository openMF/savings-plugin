/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.onboarding.data;

public enum AcquisitionStageCode {
  ONBOARDING("Commercial registration / Onboarding"),
  COMPLIANCE("File / Compliance"),
  APPROVAL("Committee / Internal Control validation"),
  ACTIVATION("Commercial / Operations activation"),
  DEPOSIT("ATM / Account deposit"),
  WITHDRAWAL("ATM / Account withdrawal");

  private final String displayName;

  AcquisitionStageCode(final String displayName) {
    this.displayName = displayName;
  }

  public String getDisplayName() {
    return displayName;
  }
}
