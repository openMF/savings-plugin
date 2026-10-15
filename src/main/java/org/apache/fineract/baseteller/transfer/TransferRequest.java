package org.apache.fineract.baseteller.transfer;

import java.math.BigDecimal;

/** IDs are qualified by account type because savings and loan IDs can overlap. */
public record TransferRequest(
    TransferOperation operationType,
    Long orderingClientId,
    Long sourceAccountId,
    TransferAccountType targetAccountType,
    Long targetAccountId,
    BigDecimal amount,
    String idempotencyKey) {}
