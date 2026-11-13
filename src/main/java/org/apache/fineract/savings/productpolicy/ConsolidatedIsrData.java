/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Immutable diagnostic contracts. Unknown financial results are null, never a guessed zero. */
public final class ConsolidatedIsrData {
  private ConsolidatedIsrData() {}

  public enum Reason {
    ISR_POLICY_NOT_APPROVED,
    TAX_PROFILE_UNVERIFIED,
    AGGREGATION_BOUNDARY_UNAPPROVED,
    TAX_PERIOD_UNSUPPORTED,
    TAXABLE_BASIS_UNAPPROVED,
    ALLOCATION_UNAPPROVED,
    ROUNDING_UNAPPROVED,
    NATIVE_ACCOUNTING_INTEGRATION_UNVERIFIED,
    ACCOUNT_CLASSIFICATION_UNSUPPORTED,
    INVESTMENT_TREATMENT_UNAPPROVED,
    ACCOUNT_STATUS_INELIGIBLE,
    ACCOUNT_OWNERSHIP_UNSUPPORTED,
    ACCOUNT_OFFICE_SCOPE_INCOMPLETE,
    ACCOUNT_POPULATION_LIMIT_EXCEEDED,
    PRODUCT_POLICY_UNAVAILABLE,
    PRODUCT_POLICY_NOT_EFFECTIVE,
    PRODUCT_POLICY_TRANSITION_UNSUPPORTED,
    PRODUCT_CORE_CHANGED,
    TAX_GROUP_UNAVAILABLE,
    TAX_CONFIGURATION_MISMATCH,
    CURRENCY_UNSUPPORTED,
    CURRENCY_AGGREGATION_UNAPPROVED,
    SOURCE_DATA_UNAVAILABLE,
    SOURCE_TRANSACTION_LIMIT_EXCEEDED,
    HISTORICAL_BALANCE_BASIS_UNAPPROVED,
    HISTORICAL_ACCOUNT_STATE_UNVERIFIED,
    NATIVE_WITHHOLDING_REMAINS_AUTHORITATIVE,
    SOURCE_DATA_CHANGED,
    NO_ACCOUNTS
  }

  public static final List<Reason> BLOCKERS =
      List.of(
          Reason.ISR_POLICY_NOT_APPROVED, Reason.TAX_PROFILE_UNVERIFIED,
          Reason.AGGREGATION_BOUNDARY_UNAPPROVED, Reason.TAX_PERIOD_UNSUPPORTED,
          Reason.TAXABLE_BASIS_UNAPPROVED, Reason.ALLOCATION_UNAPPROVED,
          Reason.ROUNDING_UNAPPROVED, Reason.NATIVE_ACCOUNTING_INTEGRATION_UNVERIFIED);

  public record Capabilities(
      boolean eligibilityDiagnostics,
      boolean nativeBalanceObservations,
      boolean consolidatedBalanceRetrieval,
      boolean draftRuleVersions,
      boolean approvedTaxRules,
      boolean calculation,
      boolean allocation,
      boolean withholdingExecution,
      boolean accountingIntegrationVerified,
      boolean historicalSnapshots,
      boolean historicalReconciliation,
      List<String> diagnosticClassifications,
      List<Reason> blockers) {}

  public static Capabilities capabilities() {
    return new Capabilities(
        true,
        true,
        false,
        true,
        false,
        false,
        false,
        false,
        false,
        true,
        true,
        List.of("SAVINGS", "INTEREST_BEARING_SIGHT"),
        BLOCKERS);
  }

  /** Caller dates describe a diagnostic window, not an approved statutory tax period. */
  public record Period(LocalDate from, LocalDate through) {}

  public record Profile(
      long clientId,
      String tenant,
      Integer legalForm,
      String jurisdiction,
      String taxTreatment,
      String ruleId,
      Long ruleVersion,
      String currency,
      String status,
      List<Long> eligibleAccounts) {}

  public record AccountDecision(
      long accountId,
      long productId,
      Long policyVersion,
      String classification,
      boolean structurallySupported,
      boolean eligible,
      List<Reason> reasons) {}

  public record Eligibility(
      long clientId,
      Period period,
      String state,
      Profile profile,
      List<AccountDecision> accounts,
      boolean populationComplete,
      List<Reason> blockers) {}

  public record Account(
      long accountId,
      long productId,
      long clientId,
      long officeId,
      int depositType,
      int status,
      int subStatus,
      LocalDate activatedOn,
      LocalDate closedOn,
      String currency,
      int currencyDigits,
      Long policyVersion,
      String classification,
      String purpose,
      Long mainAccountId,
      boolean nativeWithholdTax,
      Long taxGroupId,
      Integer accountingType,
      BigDecimal observedLedgerBalance,
      String policyEvidenceHash,
      boolean structurallySupported,
      boolean eligible,
      List<Reason> reasons) {
    public Account {
      reasons = List.copyOf(reasons);
    }
  }

  /** Native historical records only; amount is not automatically taxable income. */
  public record Transaction(
      long id,
      long accountId,
      int type,
      LocalDate date,
      BigDecimal amount,
      boolean reversed,
      boolean reversal,
      Long originalTransactionId,
      BigDecimal runningBalance) {}

  public record TaxDetail(long id, long transactionId, long componentId, BigDecimal amount) {}

  public record CurrencyObservation(
      String currency,
      int currencyDigits,
      int accountCount,
      BigDecimal observedLedgerBalance,
      BigDecimal nativePostedInterest,
      BigDecimal nativeWithheldTax) {}

  public record Sources(
      List<Account> accounts,
      List<Transaction> transactions,
      List<TaxDetail> taxDetails,
      List<CurrencyObservation> observations,
      boolean complete,
      List<Reason> reasons,
      String fingerprint) {
    public Sources {
      accounts = List.copyOf(accounts);
      transactions = List.copyOf(transactions);
      taxDetails = List.copyOf(taxDetails);
      observations = List.copyOf(observations);
      reasons = List.copyOf(reasons);
    }
  }

  public record Preview(
      long clientId,
      LocalDate businessDate,
      Period taxPeriod,
      Instant observedAt,
      Profile profile,
      Sources sources,
      String state,
      BigDecimal taxableBase,
      BigDecimal calculatedIsr,
      BigDecimal actualWithheldAmount,
      List<Object> allocations,
      List<Long> nativeReferences,
      List<Reason> blockers) {
    public Preview {
      allocations = List.copyOf(allocations);
      nativeReferences = List.copyOf(nativeReferences);
      blockers = List.copyOf(blockers);
    }
  }

  public record Save(String idempotencyKey, Period period, String supersedes, String reason) {}

  public record Calculation(
      String calculationId,
      String tenant,
      long clientId,
      long actorId,
      Instant recordedAt,
      String supersedes,
      String correctionReason,
      Preview result) {}

  public record HistoryEntry(long sequence, Calculation calculation) {}

  public record AuditEntry(
      long id,
      String action,
      long actorId,
      LocalDate businessDate,
      Instant recordedAt,
      String detailsJson) {}

  public record Reconciliation(
      String calculationId,
      String status,
      String savedFingerprint,
      String currentFingerprint,
      Instant checkedAt,
      List<Reason> blockers,
      boolean accountingVerified,
      boolean financialRetryAllowed) {}

  /** References are proposal metadata; they never establish a customer's tax treatment. */
  public record RuleDefinition(
      String jurisdiction,
      String taxTreatment,
      String currency,
      LocalDate effectiveFrom,
      LocalDate effectiveThrough,
      String specificationReference,
      String specificationSha256,
      String basisReference,
      String periodReference,
      String formulaReference,
      String allocationReference,
      String roundingReference,
      String accountingReference) {}

  public record RuleProposal(
      String idempotencyKey, Long expectedVersion, RuleDefinition definition, String reason) {}

  public record RuleVersion(
      String ruleId,
      long version,
      Long previousVersion,
      String tenant,
      String status,
      RuleDefinition definition,
      long authorId,
      Long approverId,
      Instant createdAt,
      Instant approvedAt,
      String changeReason) {}
}
