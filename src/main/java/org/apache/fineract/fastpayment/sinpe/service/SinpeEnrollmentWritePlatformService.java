package org.apache.fineract.fastpayment.sinpe.service;

import org.apache.fineract.fastpayment.sinpe.data.SinpeSubscriptionEditRequest;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;

public interface SinpeEnrollmentWritePlatformService {

  CommandProcessingResult requestEnrollment(Long clientId, String mobileNumber);

  CommandProcessingResult confirmEnrollment(Long clientId, String mobileNumber, String otp);
  
  CommandProcessingResult createSubscription(Long clientId, String phoneNumber, String iban, String otp);

  CommandProcessingResult editSubscription(Long clientId, SinpeSubscriptionEditRequest request, String otp);

  CommandProcessingResult deleteSubscription(Long clientId, String phoneNumber, String otp);
}