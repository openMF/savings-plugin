package org.apache.fineract.baseteller.data;

import java.time.LocalDate;

public record TransactionHistoryQuery(
    LocalDate fromDate,
    LocalDate toDate,
    Long tellerId,
    String currencyCode,
    String status,
    String type,
    String operation,
    String concept,
    String reference,
    Integer offset,
    Integer limit,
    String sort,
    String order) {}
