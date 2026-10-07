package org.apache.fineract.baseteller.service;

import org.apache.fineract.baseteller.data.TransactionHistoryContextData;
import org.apache.fineract.baseteller.data.TransactionHistoryDenominationsData;
import org.apache.fineract.baseteller.data.TransactionHistoryDetailData;
import org.apache.fineract.baseteller.data.TransactionHistoryQuery;
import org.apache.fineract.baseteller.data.TransactionHistoryReceiptData;
import org.apache.fineract.baseteller.data.TransactionHistoryReportData;
import org.apache.fineract.baseteller.data.TransactionHistorySearchData;

public interface TransactionHistoryReadPlatformService {
  TransactionHistoryContextData context();

  TransactionHistorySearchData search(TransactionHistoryQuery query);

  TransactionHistoryDetailData detail(String historyId);

  TransactionHistoryDenominationsData denominations(String historyId);

  TransactionHistoryReceiptData receipt(String historyId);

  TransactionHistoryReportData report(TransactionHistoryQuery query);
}
