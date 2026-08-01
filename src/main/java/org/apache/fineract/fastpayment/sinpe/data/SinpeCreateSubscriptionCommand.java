package org.apache.fineract.fastpayment.sinpe.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SinpeCreateSubscriptionCommand {
  private Long clientId;
  private String phoneNumber;
  private String iban;
  private String otp;
}