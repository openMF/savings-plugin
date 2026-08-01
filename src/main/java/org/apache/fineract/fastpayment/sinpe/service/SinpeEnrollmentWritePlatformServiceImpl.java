package org.apache.fineract.fastpayment.sinpe.service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.fastpayment.sinpe.data.SinpeSubscriptionEditRequest;
import org.apache.fineract.fastpayment.sinpe.data.SinpeSubscriptionRequest;
import org.apache.fineract.fastpayment.sinpe.domain.SinpeEnrollment;
import org.apache.fineract.fastpayment.sinpe.domain.SinpeEnrollmentRepository;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SinpeEnrollmentWritePlatformServiceImpl implements SinpeEnrollmentWritePlatformService {

  private final PlatformSecurityContext context;
  private final SinpeEnrollmentRepository enrollmentRepository;
  private final ClientRepositoryWrapper clientRepository;
  private final SavingsSinpeExternalApiClient sinpeExternalApiClient;

  @Override
  @Transactional
  public CommandProcessingResult requestEnrollment(Long clientId, String mobileNumber) {
    AppUser user = context.authenticatedUser();
    context.authenticatedUser().validateHasPermissionTo("CREATE_SINPE_ENROLLMENT");

    if (mobileNumber == null || !mobileNumber.matches("\\d{8}")) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.sinpe.mobile.invalid",
          "Invalid SINPE Móvil phone number. Must be 8 digits.");
    }

    // Ensures the client exists (throws PlatformDataIntegrityException / not-found if missing)
    clientRepository.findOneWithNotFoundDetection(clientId);

    var existingVerified =
        enrollmentRepository.findByClientIdAndMobileNumberAndVerifiedTrue(clientId, mobileNumber);
    if (existingVerified.isPresent()) {
      return new CommandProcessingResultBuilder()
          .withClientId(clientId)
          .withEntityId(existingVerified.get().getId())
          .build();
    }

    String otp = String.format("%06d", new SecureRandom().nextInt(999999));
    LocalDateTime expiry = DateUtils.getLocalDateTimeOfTenant().plusMinutes(10);

    SinpeEnrollment enrollment =
        enrollmentRepository
            .findByClientIdAndMobileNumber(clientId, mobileNumber)
            .orElse(new SinpeEnrollment(clientId, mobileNumber, user.getId()));

    enrollment.setPendingOtp(otp, expiry);
    enrollmentRepository.saveAndFlush(enrollment);

    // TODO: integrate with SMS/notification service. For now log (remove in production).
    log.info("SINPE OTP generated for clientId={}, mobile={}, otp={}", clientId, mobileNumber, otp);
    
    Map<String, Object> testingMap = new HashMap<>();

    // Add entries with different value types
    testingMap.put("mobileNumber", mobileNumber);       // String value
    testingMap.put("otp", otp);             // Integer value (autoboxed)

    return new CommandProcessingResultBuilder()
        .withClientId(clientId)
        .withEntityId(enrollment.getId())
        .with(testingMap)
        .build();
  }

  @Override
  @Transactional
  public CommandProcessingResult confirmEnrollment(Long clientId, String mobileNumber, String otp) {
    context.authenticatedUser().validateHasPermissionTo("UPDATE_SINPE_ENROLLMENT");

    SinpeEnrollment enrollment =
        enrollmentRepository
            .findByClientIdAndMobileNumber(clientId, mobileNumber)
            .orElseThrow(
                () ->
                    new GeneralPlatformDomainRuleException(
                        "error.msg.sinpe.enrollment.not.found",
                        "No enrollment request found for the given client and phone number."));

    LocalDateTime now = DateUtils.getLocalDateTimeOfTenant();
    if (!enrollment.isOtpValid(otp, now)) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.sinpe.otp.invalid", "Invalid or expired OTP.");
    }

    enrollment.markAsVerified(now);
    enrollmentRepository.saveAndFlush(enrollment);

    return new CommandProcessingResultBuilder()
        .withClientId(clientId)
        .withEntityId(enrollment.getId())
        .build();
  }

    @Override
    @Transactional
    public CommandProcessingResult createSubscription(
        Long clientId, String phoneNumber, String iban, String otp) {

      context.authenticatedUser().validateHasPermissionTo("UPDATE_SINPE_ENROLLMENT");

      // 1. Validate OTP first
      validateOtp(clientId, phoneNumber, otp);

      // 2. Load client (multi-tenant aware)
      Client client = clientRepository.findOneWithNotFoundDetection(clientId);

      // 3. Build the full external request from client data + defaults
      SinpeSubscriptionRequest request = buildSubscriptionRequest(client, phoneNumber, iban);

      // 4. Call external SINPE
      String externalResponse = sinpeExternalApiClient.createSubscription(request);

        Map<String, Object> changes = new HashMap<>();
        changes.put("phoneNumber", phoneNumber);
        changes.put("iban", iban);
        changes.put("externalResponse", externalResponse);   // <-- the real SINPE response

        return new CommandProcessingResultBuilder()
            .withClientId(clientId)
            .with(changes)
            .build();
    }

    /**
     * Enriches the minimal payload with data taken from the Client entity
     * and sensible defaults for the external SINPE API.
     */
    private SinpeSubscriptionRequest buildSubscriptionRequest(
        Client client, String phoneNumber, String iban) {

      String customerName = client.getDisplayName();
      if (StringUtils.isBlank(customerName)) {
        customerName = StringUtils.trimToEmpty(client.getFirstname()) + " "
            + StringUtils.trimToEmpty(client.getLastname());
        customerName = customerName.trim();
      }

      // Prefer externalId, fall back to account number / mobile
      String customerId = null;
      if (client.getExternalId() != null && StringUtils.isNotBlank(client.getExternalId().getValue())) {
        customerId = client.getExternalId().getValue();
      } else if (StringUtils.isNotBlank(client.getAccountNumber())) {
        customerId = client.getAccountNumber();
      } else {
        customerId = phoneNumber;
      }

      String customerEmail = client.getEmailAddress(); // may be null – external API usually tolerates it
      
      SecureRandom secureRandom = new SecureRandom();
      // Generate number between 0 (inclusive) and 100000 (exclusive)
      int num = secureRandom.nextInt(100000);
        
        // Format to 5 digits, padding with zeros if needed (e.g., 00042)
      String formatted = String.format("%05d", num);

      return SinpeSubscriptionRequest.builder()
          .phoneNumber(phoneNumber)
          .customerName(customerName)
          .customerId(customerId)
          .customerEmail(customerEmail)
          .notificationType("AMBAS")          // default – change if business requires otherwise
          .iban(iban)
          .currencyCode("CRC")                // Costa Rica default
          .dailyMaxAmountNc(0)
          .monthlyMaxAmountNc(0)
          .dailyMaxAmountNotAuth(0)
          .monthlyMaxAmountNotAuth(0)
          .dailyMaxAmountIncoming(0)
          .monthlyMaxAmountIncoming(0)
          .overwriteAmounts(false)
          .token(formatted)                        // or generate a token if the external system requires it
          .build();
    }

  @Override
  @Transactional
  public CommandProcessingResult editSubscription(
      Long clientId, SinpeSubscriptionEditRequest request, String otp) {
    context.authenticatedUser().validateHasPermissionTo("UPDATE_SINPE_ENROLLMENT");
    validateOtp(clientId, request.getPhoneNumber(), otp);
    sinpeExternalApiClient.editSubscription(request);
    return new CommandProcessingResultBuilder().withClientId(clientId).build();
  }

  @Override
  @Transactional
  public CommandProcessingResult deleteSubscription(Long clientId, String phoneNumber, String otp) {
    context.authenticatedUser().validateHasPermissionTo("DELETE_SINPE_ENROLLMENT");
    validateOtp(clientId, phoneNumber, otp);
    String externalResponse = sinpeExternalApiClient.deleteSubscription(phoneNumber);

    Map<String, Object> changes = new HashMap<>();
    changes.put("phoneNumber", phoneNumber);
    changes.put("externalResponse", externalResponse);   // <-- the real SINPE response

    return new CommandProcessingResultBuilder()
        .withClientId(clientId)
        .with(changes)
        .build();
  }

  private void validateOtp(Long clientId, String mobileNumber, String otp) {
    log.info("validateOtp called – otp=[{}], blank={}", otp, StringUtils.isBlank(otp));
    if (StringUtils.isBlank(otp)) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.sinpe.otp.required", "OTP is required for this operation.");
    }

    SinpeEnrollment enrollment =
        enrollmentRepository
            .findByClientIdAndMobileNumber(clientId, mobileNumber)
            .orElseThrow(
                () ->
                    new GeneralPlatformDomainRuleException(
                        "error.msg.sinpe.enrollment.not.found",
                        "No enrollment found for the given client and phone number."));

    LocalDateTime now = DateUtils.getLocalDateTimeOfTenant();
    if (!enrollment.isOtpValid(otp, now)) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.sinpe.otp.invalid", "Invalid or expired OTP.");
    }
    // Note: OTP is intentionally not consumed so it can be reused for edit/delete within the window
  }
}