package org.apache.fineract.fastpayment.sinpe.service;

import org.apache.fineract.fastpayment.sinpe.data.SinpePhoneStatusData;

public interface SinpeEnrollmentReadPlatformService {
  SinpePhoneStatusData retrievePhoneStatus(String phoneNumber);
}