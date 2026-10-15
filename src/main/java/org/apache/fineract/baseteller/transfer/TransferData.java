package org.apache.fineract.baseteller.transfer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Safe workflow projections; no identifiers, contact details, or current historical balances. */
public final class TransferData {
  private TransferData() {}

  public record Client(
      Long clientId,
      String clientNumber,
      String displayName,
      String clientType,
      String status,
      Long officeId,
      String officeName,
      boolean active) {}

  public record Account(
      Long accountId,
      String accountNo,
      TransferAccountType accountType,
      Client owner,
      Long productId,
      String productName,
      String currencyCode,
      int decimalPlaces,
      String status,
      BigDecimal withdrawableBalance,
      boolean canDebit,
      boolean canCredit) {}

  public record Cashier(Long tellerId, Long cashierId) {}

  public record Context(
      String businessDate,
      Long officeId,
      String officeName,
      Cashier cashier,
      List<TransferOperation> operationTypes,
      List<TransferAccountType> sourceAccountTypes,
      List<TransferAccountType> destinationAccountTypes,
      List<String> currencies,
      List<String> permissions,
      boolean sameCurrencyOnly,
      boolean physicalCashMovement) {}

  public record Preview(
      TransferOperation operationType,
      String businessDate,
      Client orderingClient,
      Client beneficiaryClient,
      Account source,
      Account destination,
      BigDecimal amount,
      BigDecimal withdrawalFee,
      Map<String, BigDecimal> outflowsByCurrency,
      Map<String, BigDecimal> inflowsByCurrency,
      boolean balanced,
      List<String> warnings) {}

  public record Receipt(
      Long transferId,
      Long transferDetailId,
      String reference,
      String businessDate,
      String completedAt,
      String status,
      TransferOperation operationType,
      Client orderingClient,
      Client beneficiaryClient,
      Account source,
      Account destination,
      BigDecimal amount,
      String currencyCode,
      Long sourceTransactionId,
      Long destinationTransactionId,
      Long operatorId,
      String operatorName,
      Long tellerId,
      Long cashierId) {}
}
