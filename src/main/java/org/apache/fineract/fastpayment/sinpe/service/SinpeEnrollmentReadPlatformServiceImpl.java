package org.apache.fineract.fastpayment.sinpe.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.fastpayment.sinpe.data.SinpePhoneStatusData;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class SinpeEnrollmentReadPlatformServiceImpl implements SinpeEnrollmentReadPlatformService {

  private final PlatformSecurityContext context;
  private final SavingsSinpeExternalApiClient sinpeExternalApiClient;
  private final Gson gson = new Gson();

  @Override
  public SinpePhoneStatusData retrievePhoneStatus(String phoneNumber) {
    context.authenticatedUser().validateHasPermissionTo("READ_SINPE_ENROLLMENT");

    if (StringUtils.isBlank(phoneNumber) || !phoneNumber.matches("\\d{8}")) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.sinpe.mobile.invalid",
          "Invalid SINPE Móvil phone number. Must be 8 digits.");
    }

    String raw = sinpeExternalApiClient.getPhoneStatus(phoneNumber);

    if (StringUtils.isBlank(raw)) {
      // Service disabled or empty response
      return SinpePhoneStatusData.builder()
          .phoneNumber(phoneNumber)
          .successful(false)
          .found(false)
          .rawResponse(raw)
          .build();
    }

    try {
      // Map the known external structure
      JsonObject json = JsonParser.parseString(raw).getAsJsonObject();

      return SinpePhoneStatusData.builder()
          .phoneNumber(getAsString(json, "phoneNumber", phoneNumber))
          .successful(getAsBoolean(json, "successful"))
          .found(getAsBoolean(json, "found"))
          .local(getAsBoolean(json, "local"))
          .entityCode(getAsString(json, "entityCode", null))
          .entityName(getAsString(json, "entityName", null))
          .holder(getAsString(json, "holder", null))
          .holderId(getAsString(json, "holderId", null))
          .rawResponse(raw)
          .build();
    } catch (Exception e) {
      log.warn("Could not parse phone status JSON for {}: {}", phoneNumber, e.getMessage());
      // Still return the raw payload so the caller can inspect it
      return SinpePhoneStatusData.builder()
          .phoneNumber(phoneNumber)
          .successful(false)
          .found(false)
          .rawResponse(raw)
          .build();
    }
  }

  private static String getAsString(JsonObject json, String member, String defaultValue) {
    if (json.has(member) && !json.get(member).isJsonNull()) {
      return json.get(member).getAsString();
    }
    return defaultValue;
  }

  private static Boolean getAsBoolean(JsonObject json, String member) {
    if (json.has(member) && !json.get(member).isJsonNull()) {
      return json.get(member).getAsBoolean();
    }
    return null;
  }
}