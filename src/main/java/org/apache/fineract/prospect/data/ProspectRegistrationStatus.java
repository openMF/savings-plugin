/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.prospect.data;

import java.util.Set;

public final class ProspectRegistrationStatus {

  public static final String PENDING = "PENDING";
  public static final String IN_PROGRESS = "IN_PROGRESS";
  public static final String SUBMITTED = "SUBMITTED";
  public static final String COMPLETED = "COMPLETED";
  public static final String REJECTED = "REJECTED";
  public static final String WITHDRAWN = "WITHDRAWN";

  private static final Set<String> VALUES =
      Set.of(PENDING, IN_PROGRESS, SUBMITTED, COMPLETED, REJECTED, WITHDRAWN);

  private ProspectRegistrationStatus() {}

  public static boolean isSupported(final String status) {
    return VALUES.contains(status);
  }

  public static Set<String> values() {
    return VALUES;
  }
}
