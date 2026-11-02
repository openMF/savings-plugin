/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Immutable review contracts; actor and tenant identifiers are never accepted as input. */
public final class ProductPolicyWorkflowData {
  private ProductPolicyWorkflowData() {}

  public record Submit(Long expectedRevision, LocalDate effectiveDate) {}

  public record Decision(String reason) {}

  public record Version(
      long savingsProductId,
      long version,
      String status,
      LocalDate effectiveDate,
      Long baseVersion,
      long makerId,
      Long checkerId,
      Instant submittedAt,
      Instant decidedAt,
      String reason,
      ProductPolicyData.Policy policy,
      java.util.Map<String, Object> coreSettings) {}

  public record Review(Version proposed, Version active, List<String> changedFields) {}

  public record Audit(
      long id,
      long savingsProductId,
      long version,
      String action,
      Long actorId,
      Instant recordedAt,
      String previousState,
      String newState,
      LocalDate effectiveDate,
      String reason,
      String snapshot) {}
}
