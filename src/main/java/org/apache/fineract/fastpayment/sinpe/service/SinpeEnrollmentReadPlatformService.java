package org.apache.fineract.fastpayment.sinpe.service;

import java.util.Collection;
import org.apache.fineract.fastpayment.sinpe.data.SinpeLinkedPhoneData;
import org.apache.fineract.fastpayment.sinpe.data.SinpePhoneStatusData;

public interface SinpeEnrollmentReadPlatformService {
  SinpePhoneStatusData retrievePhoneStatus(String phoneNumber);

  Collection<SinpeLinkedPhoneData> retrieveLinkedPhones(Long savingsAccountId);
}
