package org.apache.fineract.baseteller.service;

import java.util.List;
import org.apache.fineract.baseteller.data.BaseTellerCustomerData;
import org.apache.fineract.baseteller.data.CreditPaymentContextData;
import org.apache.fineract.baseteller.data.CreditPaymentLoanData;
import org.apache.fineract.baseteller.data.CreditPaymentReceiptData;

public interface CreditPaymentReadPlatformService {
  CreditPaymentContextData context();

  List<BaseTellerCustomerData> searchCustomers(Long clientId, String accountNo, String query, Integer limit);

  List<CreditPaymentLoanData> loans(Long clientId);

  CreditPaymentLoanData loan(Long loanId);

  CreditPaymentReceiptData receipt(String receiptNumber);
}
