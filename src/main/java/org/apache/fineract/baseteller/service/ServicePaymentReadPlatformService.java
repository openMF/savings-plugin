package org.apache.fineract.baseteller.service;

import java.util.List;
import org.apache.fineract.baseteller.data.BaseTellerCustomerData;
import org.apache.fineract.baseteller.data.ServicePaymentQuoteData;
import org.apache.fineract.baseteller.data.ServicePaymentQuoteRequest;
import org.apache.fineract.baseteller.data.ServicePaymentReceiptData;
import org.apache.fineract.baseteller.data.ServicePaymentServiceData;

public interface ServicePaymentReadPlatformService {
  List<ServicePaymentServiceData> services();

  BaseTellerCustomerData client(Long clientId);

  ServicePaymentQuoteData quote(ServicePaymentQuoteRequest request);

  ServicePaymentReceiptData receipt(Long transactionId);
}
