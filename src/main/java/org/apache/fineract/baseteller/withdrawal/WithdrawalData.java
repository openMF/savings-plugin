package org.apache.fineract.baseteller.withdrawal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.fineract.baseteller.data.EndOfDayClosingData.Cashier;
import org.apache.fineract.baseteller.withdrawal.WithdrawalRequest.Method;

/**
 * Preview and historical receipt projections; no full identity reference or current receipt
 * balance.
 */
public record WithdrawalData(
    Long id,
    String reference,
    String status,
    String businessDate,
    String completedAt,
    Customer customer,
    Account account,
    Cashier cashier,
    Long operatorId,
    String operatorName,
    Method method,
    BigDecimal amount,
    String currencyCode,
    List<Denomination> denominations,
    Identification recipient,
    BigDecimal accountDebit,
    BigDecimal physicalCashOutflow,
    Map<String, BigDecimal> inflowsByCurrency,
    Map<String, BigDecimal> outflowsByCurrency,
    Long savingsTransactionId,
    Long cashierTransactionId,
    boolean reversible) {

  public record Customer(
      Long clientId,
      String clientNumber,
      String displayName,
      String clientType,
      String status,
      Long officeId,
      String officeName,
      boolean eligible) {}

  public record Account(
      Long accountId,
      String accountNumber,
      Long productId,
      String productName,
      String currencyCode,
      int decimalPlaces,
      String status,
      BigDecimal availableAmount,
      boolean withdrawalEligible,
      List<String> restrictions) {}

  public record Identification(
      Long identifierId,
      Long identificationTypeId,
      String identificationType,
      String maskedReference) {}

  public record Option(Long id, String name) {}

  public record CheckCapability(boolean supported, String reason) {}

  public record Denomination(
      String denominationId,
      String currencyCode,
      String denominationType,
      BigDecimal denominationValue,
      long quantity,
      BigDecimal lineAmount) {}

  public record AvailableDenomination(
      String denominationId,
      String currencyCode,
      String denominationType,
      BigDecimal denominationValue,
      long availableQuantity) {}

  public record Context(
      String businessDate,
      Cashier cashier,
      List<Method> supportedMethods,
      List<String> supportedCurrencies,
      List<String> permissions,
      List<Option> identificationTypes,
      List<Option> cashPaymentTypes,
      CheckCapability checkCapabilities,
      List<AvailableDenomination> denominations,
      boolean accountOwnerOnly) {}
}
