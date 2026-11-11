/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

/**
 * Non-operational relationship vocabulary for the approval-dependent BE-08 work. No instance is
 * evidence of reserved funds, a native account, or permission to move money. Persistence and
 * monetary allocation contracts must follow the approved cardinality and lifecycle rules.
 */
public final class GuaranteeRelationshipData {
  private GuaranteeRelationshipData() {}

  public enum State {
    BLOCKED_PENDING_APPROVAL
  }

  public record PolicyReference(long savingsProductId, long version) {}

  public record LoanReference(long clientId, long loanId, String currencyCode) {}

  public record Proposal(
      MainAccountData.Relationship mainAccount,
      LoanReference loan,
      PolicyReference guaranteedProductPolicy,
      State state) {}
}
