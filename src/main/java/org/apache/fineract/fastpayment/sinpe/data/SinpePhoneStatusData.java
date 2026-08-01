package org.apache.fineract.fastpayment.sinpe.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SinpePhoneStatusData {

  private String phoneNumber;
  private Boolean successful;
  private Boolean found;
  private Boolean local;
  private String entityCode;
  private String entityName;
  private String holder;
  private String holderId;

  /** Raw JSON returned by the external system (useful for debugging). */
  private String rawResponse;
}