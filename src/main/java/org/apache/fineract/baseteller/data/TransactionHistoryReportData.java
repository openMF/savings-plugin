package org.apache.fineract.baseteller.data;

import java.time.OffsetDateTime;
import java.util.List;

public record TransactionHistoryReportData(
    OffsetDateTime generatedAt,
    List<TransactionHistoryItemData> items,
    long totalFilteredRecords,
    List<TransactionHistoryTotalsData> totalsByCurrency) {}
