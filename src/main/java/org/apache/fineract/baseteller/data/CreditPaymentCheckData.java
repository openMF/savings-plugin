package org.apache.fineract.baseteller.data;

import java.time.OffsetDateTime;

public record CreditPaymentCheckData(
    Long id,
    Long bankId,
    String bankName,
    String checkType,
    String checkNumber,
    String accountNumber,
    String routingCode,
    CreditPaymentCheckClassification classification,
    CreditPaymentStatus status,
    Long acceptedBy,
    OffsetDateTime acceptedOnUtc,
    Long clearedBy,
    OffsetDateTime clearedOnUtc,
    Long returnedBy,
    OffsetDateTime returnedOnUtc,
    String returnReason) {}
