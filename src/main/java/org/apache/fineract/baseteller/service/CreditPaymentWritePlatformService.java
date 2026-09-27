package org.apache.fineract.baseteller.service;

import org.apache.fineract.baseteller.data.CreditPaymentPreviewData;
import org.apache.fineract.baseteller.data.CreditPaymentReceiptData;
import org.apache.fineract.baseteller.data.CreditPaymentRequest;
import org.apache.fineract.baseteller.data.CreditPaymentTransitionRequest;

public interface CreditPaymentWritePlatformService {
  CreditPaymentPreviewData preview(CreditPaymentRequest request);

  CreditPaymentReceiptData create(CreditPaymentRequest request);

  CreditPaymentReceiptData clear(Long checkId, CreditPaymentTransitionRequest request);

  CreditPaymentReceiptData returnCheck(Long checkId, CreditPaymentTransitionRequest request);
}
