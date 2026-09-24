package org.apache.fineract.baseteller.service;

import org.apache.fineract.baseteller.data.ServicePaymentReceiptData;
import org.apache.fineract.baseteller.data.ServicePaymentRequest;

public interface ServicePaymentWritePlatformService {
  ServicePaymentReceiptData create(ServicePaymentRequest request);
}
