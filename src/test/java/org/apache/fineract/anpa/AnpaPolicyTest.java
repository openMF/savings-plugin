/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the Mozilla Public License, v. 2.0. If a copy of the MPL
 * was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.anpa;

import static org.apache.fineract.anpa.AnpaAccountingData.*;
import static org.apache.fineract.anpa.AnpaAccountingTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Policy input and evidence validation only; native posting is exercised by the runtime suite. */
class AnpaPolicyTest {
  final AnpaAccountingAccess access = mock(AnpaAccountingAccess.class);
  final AnpaAccountingEvidenceService evidence = mock(AnpaAccountingEvidenceService.class);
  final AnpaControlStore store = mock(AnpaControlStore.class);
  final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  final AnpaAccountingPolicyService service =
      new AnpaAccountingPolicyService(access, evidence, store, jdbc);
  final Loan loan = loan(true, 300);
  Evidence sources;

  @BeforeEach
  void setup() {
    sources = evidence(loan, List.of(mapping(1, 7, 107)), List.of(), List.of());
    when(access.authorize(anyString())).thenReturn(10L);
    when(evidence.loan(1)).thenReturn(loan);
    when(evidence.collect(loan, DAY)).thenReturn(sources);
    when(jdbc.queryForList(contains("acc_gl_account"), anyLong()))
        .thenAnswer(
            call -> {
              long id = call.getArgument(1);
              return List.of(
                  Map.of(
                      "id",
                      id,
                      "classification_enum",
                      id == 606 ? 5 : 1,
                      "account_usage",
                      1,
                      "disabled",
                      false));
            });
  }

  Map<String, Object> definition() {
    var d = new LinkedHashMap<String, Object>();
    d.putAll(
        Map.of(
            "version",
            1,
            "accountingRule",
            3,
            "currency",
            "USD",
            "effectiveFrom",
            "2026-01-01",
            "approvalReference",
            "Synthetic policy source",
            "rules",
            List.of(rule())));
    return d;
  }

  Map<String, Object> rule() {
    return new LinkedHashMap<>(
        Map.of(
            "category",
            "INTEREST",
            "historicalOrigin",
            "REGULAR",
            "currentClassification",
            "REGULAR",
            "sourceAccountId",
            107L,
            "nativeMappingId",
            1L,
            "destinationAccountId",
            606L,
            "expenseEligible",
            true));
  }

  void draft(Map<String, Object> d) {
    service.draft(
        1,
        AnpaSupport.JSON.toJson(
            Map.of("idempotencyKey", UUID.randomUUID().toString(), "policy", d)));
  }

  @Test
  void acceptsExplicitAccrualPolicyWithoutClaimingCompleteness() {
    assertDoesNotThrow(() -> draft(definition()));
    verify(access).authorize(AnpaAccountingAccess.MANAGE_POLICY);
    var policy =
        new PolicyResolution(
            "fixture",
            new PolicyDefinition(1, 3, "USD", DAY, null, "test", List.of()),
            true,
            true,
            false,
            List.of());
    assertFalse(AnpaAccountingPolicyService.complete(policy));
  }

  @Test
  void rejectsUnsupportedRuleCurrencyDatesAndUnapprovedSourceReference() {
    for (var entry :
        Map.of(
                "accountingRule",
                2,
                "currency",
                "EUR",
                "version",
                0,
                "effectiveThrough",
                "2025-01-01",
                "approvalReference",
                " ")
            .entrySet()) {
      var d = definition();
      d.put(entry.getKey(), entry.getValue());
      assertThrows(RuntimeException.class, () -> draft(d), entry.getKey());
    }
  }

  @Test
  void rejectsForeignNativeMappingDuplicateRulesAndUnknownCategories() {
    var foreign = rule();
    foreign.put("nativeMappingId", 99L);
    var d = definition();
    d.put("rules", List.of(foreign));
    assertThrows(RuntimeException.class, () -> draft(d));
    d.put("rules", List.of(rule(), rule()));
    assertThrows(RuntimeException.class, () -> draft(d));
    var unknown = rule();
    unknown.put("category", "UNKNOWN");
    d.put("rules", List.of(unknown));
    assertThrows(RuntimeException.class, () -> draft(d));
  }

  @Test
  void rejectsMissingDisabledOrWrongTypeAccounts() {
    when(jdbc.queryForList(contains("acc_gl_account"), eq(107L))).thenReturn(List.of());
    assertThrows(RuntimeException.class, () -> draft(definition()));
    for (var row :
        List.<Map<String, Object>>of(
            Map.of("id", 107L, "classification_enum", 1, "account_usage", 1, "disabled", true),
            Map.of("id", 107L, "classification_enum", 4, "account_usage", 1, "disabled", false),
            Map.of("id", 107L, "classification_enum", 1, "account_usage", 2, "disabled", false))) {
      when(jdbc.queryForList(contains("acc_gl_account"), eq(107L))).thenReturn(List.of(row));
      assertThrows(RuntimeException.class, () -> draft(definition()));
    }
  }

  @Test
  void taxSemanticsRequireExplicitComponentAndItsNativeMapping() {
    var tax = rule();
    tax.put("category", "ITBMS");
    tax.remove("nativeMappingId");
    var d = definition();
    d.put("rules", List.of(tax));
    assertThrows(RuntimeException.class, () -> draft(d));
    tax.put("taxComponentId", 12L);
    when(jdbc.queryForList(contains("m_tax_component"), eq(12L))).thenReturn(List.of());
    assertThrows(RuntimeException.class, () -> draft(d));
    when(jdbc.queryForList(contains("m_tax_component"), eq(12L)))
        .thenReturn(
            List.of(Map.of("id", 12L, "debit_account_id", 998L, "credit_account_id", 999L)));
    assertThrows(RuntimeException.class, () -> draft(d));
  }

  @Test
  void policyAndProposalParsersRejectUnsupportedFinancialOverrides() {
    var d = definition();
    d.put("balance", 100);
    assertThrows(RuntimeException.class, () -> draft(d));
    var invalid = definition();
    var r = rule();
    r.put("amount", 100);
    invalid.put("rules", List.of(r));
    assertThrows(RuntimeException.class, () -> draft(invalid));
  }

  @Test
  void rejectsCoercedBooleanFractionalAndOverflowingIdentifiers() {
    for (Object value :
        List.of("107", 107.5, new java.math.BigInteger("99999999999999999999999"))) {
      var d = definition();
      var r = rule();
      r.put("sourceAccountId", value);
      d.put("rules", List.of(r));
      assertThrows(RuntimeException.class, () -> draft(d));
    }
    var d = definition();
    var r = rule();
    r.put("expenseEligible", "true");
    d.put("rules", List.of(r));
    assertThrows(RuntimeException.class, () -> draft(d));
  }
}
