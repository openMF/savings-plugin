/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import java.time.LocalDateTime;
import java.util.List;

/** Relationship metadata only; a Main designation does not authorize a financial command. */
public final class MainAccountData {
  private MainAccountData() {}

  public record Relationship(
      long clientId,
      long savingsAccountId,
      long savingsProductId,
      long policyVersion,
      int nativeStatus,
      boolean active) {}

  public record Audit(
      long id,
      long clientId,
      long savingsAccountId,
      long savingsProductId,
      long policyVersion,
      String action,
      long actorId,
      String tenantIdentifier,
      LocalDateTime recordedAt) {}

  public record Capabilities(
      boolean mainAccountRelationships,
      boolean guaranteedAccountCreation,
      boolean guaranteeAllocation,
      boolean guaranteeRelease,
      boolean loanDisbursementRouting,
      boolean paymentReferenceProvider,
      List<String> blockers) {}

  public static Capabilities capabilities() {
    return new Capabilities(
        true,
        false,
        false,
        false,
        false,
        false,
        List.of(
            "GUARANTEE_ALLOCATION_AND_RELEASE_RULES_UNAPPROVED",
            "LOAN_LIFECYCLE_AND_DISBURSEMENT_ROUTING_RULES_UNAPPROVED",
            "NATIVE_ADJUSTMENT_AND_REVERSAL_HAVE_NO_VALIDATION_EVENT",
            "FREEZING_LIFECYCLE_AND_COMPLETE_NATIVE_COVERAGE_UNVERIFIED",
            "PAYMENT_REFERENCE_PROVIDER_AND_FORMAT_UNAPPROVED"));
  }
}
