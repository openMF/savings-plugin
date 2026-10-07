package org.apache.fineract.baseteller.data;

import java.util.List;

public record TransactionHistoryDenominationsData(
    String historyId,
    boolean operationDenominationsSupported,
    List<TransactionHistoryDenominationLineData> operationDenominations,
    boolean changeDenominationsSupported,
    List<TransactionHistoryDenominationLineData> changeDenominations) {}
