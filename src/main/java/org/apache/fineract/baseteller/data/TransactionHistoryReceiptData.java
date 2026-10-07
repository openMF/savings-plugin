package org.apache.fineract.baseteller.data;

public record TransactionHistoryReceiptData(
    String historyId, String sourceType, Long sourceId, Object receipt) {}
