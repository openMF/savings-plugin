package org.apache.fineract.fastpayment.sinpe.service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
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

    return new CommandProcessingResultBuilder()
        .withClientId(clientId)
        .withEntityId(enrollment.getId())
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
      Long clientId, SinpeSubscriptionRequest request, String otp) {
    context.authenticatedUser().validateHasPermissionTo("UPDATE_SINPE_ENROLLMENT");
    validateOtp(clientId, request.getPhoneNumber(), otp);
    sinpeExternalApiClient.createSubscription(request);
    return new CommandProcessingResultBuilder().withClientId(clientId).build();
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
    sinpeExternalApiClient.deleteSubscription(phoneNumber);
    return new CommandProcessingResultBuilder().withClientId(clientId).build();
  }

  private void validateOtp(Long clientId, String mobileNumber, String otp) {
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