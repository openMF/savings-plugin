package org.apache.fineract.baseteller.data;

import java.util.List;

public record TransactionHistorySearchData(
    List<TransactionHistoryItemData> items,
    long totalFilteredRecords,
    int offset,
    int limit,
    List<TransactionHistoryTotalsData> totalsByCurrency) {}
