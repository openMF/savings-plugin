/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.apache.fineract.investment.InvestmentProductData.*;
import static org.apache.fineract.investment.InvestmentProductFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InvestmentProductValidationTest {
  final InvestmentProductValidation validation = new InvestmentProductValidation();

  Policy policy(Map<String, Object> fields) {
    return validation.parse(request(0, fields)).policy();
  }

  void invalid(Map<String, Object> fields) {
    assertThrows(PlatformApiDataValidationException.class, () -> policy(fields));
  }

  @Test
  void promissoryNoteClassificationAndInclusiveLimits() {
    var p = policy(Map.of());
    assertEquals(InvestmentType.PROMISSORY_NOTE, p.investmentType());
    validation.validateAgainstNative(p, nativeProduct());
    validation.validateTerm(p, 1);
    validation.validateTerm(p, 1825);
    assertThrows(PlatformApiDataValidationException.class, () -> validation.validateTerm(p, 0));
    assertThrows(PlatformApiDataValidationException.class, () -> validation.validateTerm(p, 1826));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1826, -1})
  void rejectsPromissoryNoteOutOfRange(int term) {
    invalid(Map.of("minimumTermDays", term, "maximumTermDays", term));
  }

  @ParameterizedTest
  @ValueSource(ints = {60, 90, 120, 150, 180})
  void acceptsCedeMultiples(int term) {
    var p =
        policy(
            Map.of(
                "investmentType",
                "CEDE",
                "minimumTermDays",
                60,
                "maximumTermDays",
                180,
                "termMultipleDays",
                30));
    assertEquals(InvestmentType.CEDE, p.investmentType());
    validation.validateTerm(p, term);
    validation.validateAgainstNative(p, nativeProduct());
  }

  @ParameterizedTest
  @ValueSource(ints = {45, 61, 75, 95})
  void rejectsInvalidCedeTerms(int term) {
    var p =
        policy(
            Map.of(
                "investmentType",
                "CEDE",
                "minimumTermDays",
                60,
                "maximumTermDays",
                180,
                "termMultipleDays",
                30));
    assertThrows(PlatformApiDataValidationException.class, () -> validation.validateTerm(p, term));
  }

  @Test
  void cedeTraditionalMinimumIsSuggestedRatherThanUniversal() {
    policy(
        Map.of(
            "investmentType",
            "CEDE",
            "minimumTermDays",
            30,
            "maximumTermDays",
            90,
            "termMultipleDays",
            30));
    invalid(Map.of("investmentType", "CEDE"));
  }

  @Test
  void validExactAmountsAndInheritedNativeBounds() {
    validation.validateAgainstNative(
        policy(
            Map.of(
                "minimumInvestmentAmount",
                new BigDecimal("100.00"),
                "maximumInvestmentAmount",
                new BigDecimal("100000"))),
        nativeProduct());
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            validation.validateAgainstNative(
                policy(Map.of("minimumInvestmentAmount", new BigDecimal("99"))), nativeProduct()));
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            validation.validateAgainstNative(
                policy(Map.of("maximumInvestmentAmount", new BigDecimal("100001"))),
                nativeProduct()));
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            validation.validateAgainstNative(
                policy(Map.of("maximumInvestmentAmount", new BigDecimal("50"))), nativeProduct()));
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            validation.validateAgainstNative(
                policy(Map.of("minimumInvestmentAmount", new BigDecimal("100.001"))),
                nativeProduct()));
    invalid(Map.of("minimumInvestmentAmount", 0));
    invalid(Map.of("minimumInvestmentAmount", -1));
    invalid(Map.of("minimumInvestmentAmount", 200, "maximumInvestmentAmount", 100));
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            validation.validateAgainstNative(
                policy(Map.of("currencyCode", "USD")), nativeProduct()));
  }

  @Test
  void nativeTermSubsetAndOffsetAreEnforced() {
    var restricted = nativeProduct(new NativeTerms(7, 0, 100, 0, 7, 0), 2, mappings());
    validation.validateAgainstNative(
        policy(Map.of("minimumTermDays", 7, "maximumTermDays", 98, "termMultipleDays", 7)),
        restricted);
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> validation.validateAgainstNative(policy(Map.of()), restricted));
    var offset = nativeProduct(new NativeTerms(1, 0, 100, 0, 30, 0), 2, mappings());
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            validation.validateAgainstNative(
                policy(
                    Map.of("minimumTermDays", 60, "maximumTermDays", 90, "termMultipleDays", 30)),
                offset));
    var calendar = nativeProduct(new NativeTerms(1, 2, 12, 2, null, null), 2, mappings());
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> validation.validateAgainstNative(policy(Map.of()), calendar));
    var weeks = nativeProduct(new NativeTerms(1, 1, 2, 1, 1, 1), 2, mappings());
    validation.validateAgainstNative(
        policy(Map.of("minimumTermDays", 7, "maximumTermDays", 14, "termMultipleDays", 7)), weeks);
  }

  @Test
  void sevenDay52RenewalsIsConfigurableWithoutUniversalDefaults() {
    var p =
        policy(
            Map.of(
                "minimumTermDays",
                7,
                "maximumTermDays",
                7,
                "termMultipleDays",
                7,
                "automaticReinvestmentEnabled",
                true,
                "maximumReinvestments",
                52,
                "renewalRatePolicy",
                "RATE_EFFECTIVE_AT_RENEWAL",
                "maximumReinvestmentsByTerm",
                List.of(Map.of("termDays", 7, "maximumReinvestments", 52))));
    assertEquals(52, p.maximumReinvestments());
    invalid(Map.of("maximumReinvestments", 52));
    invalid(Map.of("automaticReinvestmentEnabled", true));
    invalid(
        Map.of(
            "automaticReinvestmentEnabled",
            true,
            "maximumReinvestments",
            -1,
            "renewalRatePolicy",
            "RATE_EFFECTIVE_AT_RENEWAL"));
    invalid(
        Map.of(
            "automaticReinvestmentEnabled",
            true,
            "maximumReinvestments",
            1,
            "renewalRatePolicy",
            "RATE_EFFECTIVE_AT_RENEWAL",
            "maximumReinvestmentsByTerm",
            List.of(Map.of("termDays", 7, "maximumReinvestments", 2))));
    invalid(
        Map.of(
            "automaticReinvestmentEnabled",
            true,
            "maximumReinvestments",
            1,
            "renewalRatePolicy",
            "RATE_EFFECTIVE_AT_RENEWAL",
            "maximumReinvestmentsByTerm",
            List.of(Map.of("termDays", 0, "maximumReinvestments", 1))));
    invalid(
        Map.of(
            "automaticReinvestmentEnabled",
            true,
            "maximumReinvestments",
            1,
            "renewalRatePolicy",
            "RATE_EFFECTIVE_AT_RENEWAL",
            "maximumReinvestmentsByTerm",
            List.of(
                Map.of("termDays", 7, "maximumReinvestments", 1),
                Map.of("termDays", 7, "maximumReinvestments", 1))));
    policy(
        Map.of(
            "automaticReinvestmentEnabled",
            true,
            "maximumReinvestments",
            0,
            "renewalRatePolicy",
            "RATE_EFFECTIVE_AT_RENEWAL"));
  }

  @Test
  void capitalizationAndPayoutRemainExplicitMetadata() {
    invalid(Map.of("capitalizationEnabled", true));
    invalid(Map.of("interestPayoutMode", "CAPITALIZE_ON_RENEWAL"));
    policy(
        Map.of(
            "capitalizationEligible",
            true,
            "capitalizationEnabled",
            true,
            "interestPayoutMode",
            "CAPITALIZE_ON_RENEWAL",
            "automaticReinvestmentEnabled",
            true,
            "maximumReinvestments",
            52,
            "renewalRatePolicy",
            "RATE_EFFECTIVE_AT_RENEWAL"));
    validation.validateAgainstNative(
        policy(Map.of("interestPayoutMode", "MONTHLY")), nativeProduct());
  }

  @Test
  void cancellationOnlyAcceptsConsistentVersionedReferences() {
    invalid(Map.of("penaltyMode", "NATIVE"));
    invalid(Map.of("earlyCancellationAllowed", true, "penaltyMode", "VERSIONED_REFERENCE"));
    policy(
        Map.of(
            "earlyCancellationAllowed",
            true,
            "penaltyMode",
            "VERSIONED_REFERENCE",
            "cancellationPolicyReference",
            Map.of("policyCode", "institution-cancellation", "revision", 1)));
    invalid(
        Map.of("cancellationPolicyReference", Map.of("policyCode", "institution", "revision", 1)));
    invalid(
        Map.of(
            "earlyCancellationAllowed",
            true,
            "penaltyMode",
            "VERSIONED_REFERENCE",
            "cancellationPolicyReference",
            Map.of("policyCode", "institution", "revision", 0)));
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            validation.validateAgainstNative(
                policy(Map.of("earlyCancellationAllowed", true, "penaltyMode", "NATIVE")),
                nativeProduct()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "[]",
        "{",
        "null",
        "{expectedRevision:0}",
        "{\"expectedRevision\":0,\"expectedRevision\":0}",
        "{\"expectedRevision\":0} {}"
      })
  void rejectsMalformedOrIncompleteJson(String body) {
    assertThrows(PlatformApiDataValidationException.class, () -> validation.parse(body));
  }

  @ParameterizedTest
  @ValueSource(strings = {"1.1", "1e2", "\"1\"", "true", "null", "-1", "2147483648"})
  void rejectsCoercedIntegers(String value) {
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            validation.parse(
                JSON.replace("\"minimumTermDays\":1", "\"minimumTermDays\":" + value)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"1e2", "NaN", "Infinity", "\"100\"", "-1", "10000000000000", "0.1234567"})
  void rejectsInvalidMonetaryRepresentations(String amount) {
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            validation.parse(
                JSON.replace(
                    "\"currencyCode\":\"MXN\"",
                    "\"currencyCode\":\"MXN\",\"minimumInvestmentAmount\":" + amount)));
  }

  @Test
  void unknownFieldsAndTypesProduceFieldErrors() {
    for (var body :
        List.of(
            request(0, Map.of("active", true)),
            request(0, Map.of("automaticReinvestmentEnabled", "false")),
            request(0, Map.of("investmentType", "UNKNOWN")),
            request(
                0,
                Map.of(
                    "maximumReinvestmentsByTerm",
                    List.of(Map.of("termDays", 7, "maximumReinvestments", 1, "extra", true)))))) {
      var error =
          assertThrows(PlatformApiDataValidationException.class, () -> validation.parse(body));
      assertFalse(error.getErrors().isEmpty());
    }
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            validation.parse(
                JSON.replace(
                    "\"expectedRevision\":0", "\"expectedRevision\":9223372036854775807")));
  }

  @Test
  void reportsActualAccountingBlockersAndAllFinancialCapabilitiesFalse() {
    assertTrue(InvestmentProductAccess.accountingBlockers(nativeProduct()).isEmpty());
    assertFalse(
        InvestmentProductAccess.accountingBlockers(
                nativeProduct(new NativeTerms(1, 0, null, null, null, null), 1, List.of()))
            .isEmpty());
    assertFalse(
        InvestmentProductAccess.accountingBlockers(
                nativeProduct(new NativeTerms(1, 0, null, null, null, null), 2, List.of()))
            .isEmpty());
    var mappings = new java.util.ArrayList<>(mappings());
    var old = mappings.getFirst();
    mappings.set(
        0,
        new AccountingMapping(
            old.mappingId(),
            old.financialAccountType(),
            old.glAccountId(),
            true,
            old.classification(),
            old.usage(),
            null,
            null));
    assertFalse(
        InvestmentProductAccess.accountingBlockers(
                nativeProduct(new NativeTerms(1, 0, null, null, null, null), 2, mappings))
            .isEmpty());
    var cap = capabilities();
    assertTrue(cap.configurationSupported());
    assertTrue(cap.accountingMappingVerificationSupported());
    assertFalse(cap.activationSupported());
    assertFalse(cap.financialExecutionSupported());
    assertFalse(cap.investmentOpeningSupported());
    assertFalse(cap.automaticRenewalExecutionSupported());
    assertFalse(cap.earlyCancellationExecutionSupported());
  }

  @Test
  void accountingRejectsWrongClassificationHeadersAndDuplicateRegularRoles() {
    var bad = new java.util.ArrayList<>(mappings());
    var original = bad.getFirst();
    bad.set(
        0,
        new AccountingMapping(
            original.mappingId(),
            original.financialAccountType(),
            original.glAccountId(),
            false,
            99,
            2,
            null,
            null));
    assertFalse(
        InvestmentProductAccess.accountingBlockers(
                nativeProduct(new NativeTerms(1, 0, null, null, null, null), 2, bad))
            .isEmpty());
    var duplicate = new java.util.ArrayList<>(mappings());
    duplicate.add(original);
    assertFalse(
        InvestmentProductAccess.accountingBlockers(
                nativeProduct(new NativeTerms(1, 0, null, null, null, null), 2, duplicate))
            .isEmpty());
    var accrual = new java.util.ArrayList<>(mappings());
    for (int role : new int[] {15, 16, 17})
      accrual.add(
          new AccountingMapping(role, role, (long) role, false, role == 17 ? 2 : 1, 1, null, null));
    assertTrue(
        InvestmentProductAccess.accountingBlockers(
                nativeProduct(new NativeTerms(1, 0, null, null, null, null), 3, accrual))
            .isEmpty());
    assertFalse(
        InvestmentProductAccess.accountingBlockers(
                nativeProduct(new NativeTerms(1, 0, null, null, null, null), 3, mappings()))
            .isEmpty());
  }

  @Test
  void monthlyPayoutRequiresCompatiblePostingAndNativePenaltyModeRequiresDetails() {
    var core = nativeProduct();
    var incompatiblePosting =
        new NativeProduct(
            core.productId(),
            core.name(),
            core.currencyCode(),
            core.currencyDigits(),
            core.currencyMultiple(),
            core.terms(),
            core.minimumDepositAmount(),
            core.maximumDepositAmount(),
            core.defaultDepositAmount(),
            core.preClosure(),
            core.accountingRule(),
            false,
            5,
            core.interestCompoundingPeriod(),
            false,
            null,
            core.accountingMappings());
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            validation.validateAgainstNative(
                policy(Map.of("interestPayoutMode", "MONTHLY")), incompatiblePosting));
    var penal =
        new NativeProduct(
            core.productId(),
            core.name(),
            core.currencyCode(),
            core.currencyDigits(),
            core.currencyMultiple(),
            core.terms(),
            core.minimumDepositAmount(),
            core.maximumDepositAmount(),
            core.defaultDepositAmount(),
            new NativePenalty(true, new BigDecimal("1"), 1),
            core.accountingRule(),
            false,
            4,
            core.interestCompoundingPeriod(),
            false,
            null,
            core.accountingMappings());
    validation.validateAgainstNative(
        policy(Map.of("earlyCancellationAllowed", true, "penaltyMode", "NATIVE")), penal);
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            validation.validateAgainstNative(
                policy(Map.of("earlyCancellationAllowed", true)), penal));
  }

  @Test
  void decimalCurrenciesKeepCentsWhileZeroDecimalMultiplesMustBeExact() {
    validation.validateAgainstNative(
        policy(Map.of("minimumInvestmentAmount", new BigDecimal("100.01"))), nativeProduct());
    var core = nativeProduct();
    var whole =
        new NativeProduct(
            core.productId(),
            core.name(),
            core.currencyCode(),
            0,
            5,
            core.terms(),
            core.minimumDepositAmount(),
            core.maximumDepositAmount(),
            core.defaultDepositAmount(),
            core.preClosure(),
            core.accountingRule(),
            false,
            4,
            1,
            false,
            null,
            core.accountingMappings());
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            validation.validateAgainstNative(
                policy(Map.of("minimumInvestmentAmount", 101)), whole));
    validation.validateAgainstNative(policy(Map.of("minimumInvestmentAmount", 105)), whole);
  }

  @Test
  void promissoryNoteCanNarrowToNativeMinimumRelativeIncrements() {
    var core = nativeProduct(new NativeTerms(1, 0, 1825, 0, 30, 0), 2, mappings());
    var p = policy(Map.of("minimumTermDays", 1, "maximumTermDays", 1801, "termMultipleDays", 30));
    validation.validateAgainstNative(p, core);
    validation.validateTerm(p, 1);
    validation.validateTerm(p, 31);
    validation.validateTerm(p, 1801);
    assertThrows(PlatformApiDataValidationException.class, () -> validation.validateTerm(p, 30));
  }
}
