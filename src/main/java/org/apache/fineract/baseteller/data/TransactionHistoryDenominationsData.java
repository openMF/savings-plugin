package org.apache.fineract.baseteller.data;

import java.util.List;

public record TransactionHistoryDenominationsData(
    String historyId,
    boolean operationDenominationsSupported,
    List<TransactionHistoryDenominationLineData> operationDenominations,
    boolean changeDenominationsSupported,
    List<TransactionHistoryDenominationLineData> changeDenominations,
    List<TransactionHistoryDenominationLineData> receivedDenominations,
    List<TransactionHistoryDenominationLineData> deliveredDenominations) {
  public TransactionHistoryDenominationsData(
      String historyId,
      boolean operationDenominationsSupported,
      List<TransactionHistoryDenominationLineData> operationDenominations,
      boolean changeDenominationsSupported,
      List<TransactionHistoryDenominationLineData> changeDenominations) {
    this(
        historyId,
        operationDenominationsSupported,
        operationDenominations,
        changeDenominationsSupported,
        changeDenominations,
        List.of(),
        List.of());
  }
}
