package org.apache.fineract.fastpayment.sinpe.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SinpeSubscriptionEditRequest {
  private String phoneNumber;
  private SinpeSubscriptionEditData dataToEdit;
}