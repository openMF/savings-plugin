package org.apache.fineract.baseteller.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.baseteller.data.BaseTellerDenominationData;
import org.apache.fineract.baseteller.data.TransactionReversalData.Check;

/** A handler owns source eligibility and calls the original authoritative reversal mechanism. */
public interface TransactionReversalHandler {
  String sourceType();

  Source load(Long sourceId);

  String accountingStrategy();

  String reverse(Source source, String idempotencyKey);

  default void validatePhysicalComponents(
      Source source,
      List<BaseTellerDenominationData> removed,
      List<BaseTellerDenominationData> returned) {}

  record Source(
      Long id,
      Long officeId,
      Long cashierId,
      Long accountId,
      Long transactionId,
      LocalDate businessDate,
      String status,
      String currency,
      BigDecimal amount,
      BigDecimal cashAmount,
      BigDecimal checkAmount,
      String originalDirection,
      String user,
      String operation,
      String ineligibleReason,
      List<Check> checks,
      List<BaseTellerDenominationData> returnedDenominations) {}
}
