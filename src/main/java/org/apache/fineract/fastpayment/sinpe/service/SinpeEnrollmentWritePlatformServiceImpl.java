package org.apache.fineract.fastpayment.sinpe.service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.fastpayment.sinpe.data.SinpeSubscriptionEditRequest;
import org.apache.fineract.fastpayment.sinpe.data.SinpeSubscriptionRequest;
import org.apache.fineract.fastpayment.sinpe.domain.SinpeEnrollment;
import org.apache.fineract.fastpayment.sinpe.domain.SinpeEnrollmentRepository;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepository;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SinpeEnrollmentWritePlatformServiceImpl
    implements SinpeEnrollmentWritePlatformService {

  /** Local status used for active provider-confirmed links. */
  private static final String LINKED = "LINKED";

  /** Six-digit OTP display format from the existing phone flow. */
  private static final String OTP_FORMAT = "%06d";

  /** Upper bound for generated six-digit OTP values. */
  private static final int OTP_BOUND = 1_000_000;

  /** Existing OTP validity window in minutes. */
  private static final int OTP_EXPIRY_MINUTES = 10;

  /** Shared generator for enrollment OTP values. */
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  /** Security context used for SINPE enrollment permissions. */
  private final PlatformSecurityContext context;

  /** Enrollment repository for OTP and local link state. */
  private final SinpeEnrollmentRepository enrollmentRepository;

  /** Client repository wrapper used for existing client validation. */
  private final ClientRepositoryWrapper clientRepository;

  /** Savings account repository used to validate the supplied IBAN. */
  private final SavingsAccountRepository savingsAccountRepository;

  /** Existing external API client for the SINPE phone provider. */
  private final SavingsSinpeExternalApiClient sinpeExternalApiClient;

  /**
   * Requests an OTP for the existing phone enrollment contract.
   *
   * @param clientId client requesting enrollment
   * @param mobileNumber phone number being enrolled
   * @return command result for the request
   */
  @Override
  @Transactional
  public CommandProcessingResult requestEnrollment(
      final Long clientId,
      final String mobileNumber) {
    return requestEnrollment(clientId, mobileNumber, null);
  }

  /**
   * Requests enrollment for exactly one submitted identifier.
   *
   * @param clientId client requesting enrollment
   * @param mobileNumber phone number supplied by the caller
   * @param emailAddress email supplied by the caller
   * @return command result for the request
   */
  @Override
  @Transactional
  public CommandProcessingResult requestEnrollment(
      final Long clientId,
      final String mobileNumber,
      final String emailAddress) {
    AppUser user = context.authenticatedUser();
    context.authenticatedUser()
        .validateHasPermissionTo("CREATE_SINPE_ENROLLMENT");

    validateSupportedIdentifier(mobileNumber, emailAddress, "mobileNumber");

    // Ensures the client exists before any provider enrollment can continue.
    clientRepository.findOneWithNotFoundDetection(clientId);

    var existingVerified =
        enrollmentRepository.findByClientIdAndMobileNumberAndVerifiedTrue(
            clientId, mobileNumber);
    LocalDateTime now = DateUtils.getLocalDateTimeOfTenant();
    if (existingVerified.isPresent()
        && !existingVerified.get().isLinked()
        && existingVerified.get().isOtpValid(
            existingVerified.get().getPendingOtp(), now)) {
      return new CommandProcessingResultBuilder()
          .withClientId(clientId)
          .withEntityId(existingVerified.get().getId())
          .build();
    }

    String otp = String.format(OTP_FORMAT, SECURE_RANDOM.nextInt(OTP_BOUND));
    LocalDateTime expiry = now.plusMinutes(OTP_EXPIRY_MINUTES);

    SinpeEnrollment enrollment =
        enrollmentRepository
            .findByClientIdAndMobileNumber(clientId, mobileNumber)
            .orElseGet(
                () ->
                    new SinpeEnrollment(clientId, mobileNumber, user.getId()));

    enrollment.setPendingOtp(otp, expiry);
    enrollmentRepository.saveAndFlush(enrollment);

    Map<String, Object> changes = new HashMap<>();
    changes.put("mobileNumber", mobileNumber);
    changes.put("otp", otp);

    return new CommandProcessingResultBuilder()
        .withClientId(clientId)
        .withEntityId(enrollment.getId())
        .with(changes)
        .build();
  }

  /**
   * Confirms the OTP generated by the existing phone enrollment flow.
   *
   * @param clientId client requesting enrollment
   * @param mobileNumber phone number being confirmed
   * @param otp submitted OTP
   * @return command result for the confirmation
   */
  @Override
  @Transactional
  public CommandProcessingResult confirmEnrollment(
      final Long clientId,
      final String mobileNumber,
      final String otp) {
    context.authenticatedUser()
        .validateHasPermissionTo("UPDATE_SINPE_ENROLLMENT");

    SinpeEnrollment enrollment =
        enrollmentRepository
            .findByClientIdAndMobileNumber(clientId, mobileNumber)
            .orElseThrow(
                () ->
                    new GeneralPlatformDomainRuleException(
                        "error.msg.sinpe.enrollment.not.found",
                        "No enrollment request found for the given client "
                            + "and phone number."));

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

  /**
   * Creates a phone subscription through the existing provider client.
   *
   * @param clientId client owning the savings account
   * @param phoneNumber phone number being linked
   * @param iban savings account IBAN/external ID
   * @param otp submitted OTP
   * @return command result for the subscription
   */
  @Override
  @Transactional
  public CommandProcessingResult createSubscription(
      final Long clientId,
      final String phoneNumber,
      final String iban,
      final String otp) {
    return createSubscription(clientId, phoneNumber, null, iban, otp);
  }

  /**
   * Creates a subscription after validating the submitted identifier.
   *
   * @param clientId client owning the savings account
   * @param phoneNumber phone number being linked
   * @param emailAddress email supplied by the caller
   * @param iban savings account IBAN/external ID
   * @param otp submitted OTP
   * @return command result for the subscription
   */
  @Override
  @Transactional
  public CommandProcessingResult createSubscription(
      final Long clientId,
      final String phoneNumber,
      final String emailAddress,
      final String iban,
      final String otp) {

    context.authenticatedUser()
        .validateHasPermissionTo("UPDATE_SINPE_ENROLLMENT");

    validateSupportedIdentifier(phoneNumber, emailAddress, "phoneNumber");

    // 1. Validate OTP first
    SinpeEnrollment enrollment = validateOtp(clientId, phoneNumber, otp);

    // 2. Load client (multi-tenant aware)
    Client client = clientRepository.findOneWithNotFoundDetection(clientId);
    SavingsAccount savingsAccount = validateSavingsAccount(clientId, iban);
    validateDuplicateLink(enrollment, phoneNumber, savingsAccount.getId());

    // 3. Build the full external request from client data + defaults
    SinpeSubscriptionRequest request =
        buildSubscriptionRequest(client, phoneNumber, iban, otp);

    // 4. Call external SINPE
    String externalResponse =
        sinpeExternalApiClient.createSubscription(request);
    if (StringUtils.isBlank(externalResponse)) {
      throw providerFailure();
    }

    enrollment.markAsLinked(
        savingsAccount.getId(), iban, LocalDateTime.now(ZoneOffset.UTC));
    enrollmentRepository.saveAndFlush(enrollment);

    Map<String, Object> changes = new HashMap<>();
    changes.put("phoneNumber", phoneNumber);
    changes.put("iban", iban);
    changes.put("savingsAccountId", savingsAccount.getId());

    return new CommandProcessingResultBuilder()
        .withClientId(clientId)
        .withEntityId(enrollment.getId())
        .with(changes)
        .build();
  }

  /**
   * Enriches the minimal payload with data taken from the client entity.
   *
   * @param client client owning the subscription
   * @param phoneNumber phone number to link
   * @param iban savings account IBAN/external ID
   * @param otp submitted OTP passed to the provider token field
   * @return provider subscription request
   */
  private SinpeSubscriptionRequest buildSubscriptionRequest(
      final Client client,
      final String phoneNumber,
      final String iban,
      final String otp) {

    String customerName = client.getDisplayName();
    if (StringUtils.isBlank(customerName)) {
      customerName = StringUtils.trimToEmpty(client.getFirstname()) + " "
          + StringUtils.trimToEmpty(client.getLastname());
      customerName = customerName.trim();
    }

    String customerId = null;
    if (client.getExternalId() != null
        && StringUtils.isNotBlank(client.getExternalId().getValue())) {
      customerId = client.getExternalId().getValue();
    } else if (StringUtils.isNotBlank(client.getAccountNumber())) {
      customerId = client.getAccountNumber();
    } else {
      customerId = phoneNumber;
    }

    String customerEmail = client.getEmailAddress();

    return SinpeSubscriptionRequest.builder()
        .phoneNumber(phoneNumber)
        .customerName(customerName)
        .customerId(customerId)
        .customerEmail(customerEmail)
        .notificationType("AMBAS")
        .iban(iban)
        .currencyCode("CRC")
        .dailyMaxAmountNc(0)
        .monthlyMaxAmountNc(0)
        .dailyMaxAmountNotAuth(0)
        .monthlyMaxAmountNotAuth(0)
        .dailyMaxAmountIncoming(0)
        .monthlyMaxAmountIncoming(0)
        .overwriteAmounts(false)
        .token(otp)
        .build();
  }

  /**
   * Preserves the existing phone subscription edit operation.
   *
   * @param clientId client owning the subscription
   * @param request provider edit payload
   * @param otp submitted OTP
   * @return command result for the edit operation
   */
  @Override
  @Transactional
  public CommandProcessingResult editSubscription(
      final Long clientId,
      final SinpeSubscriptionEditRequest request,
      final String otp) {
    context.authenticatedUser()
        .validateHasPermissionTo("UPDATE_SINPE_ENROLLMENT");
    validateOtp(clientId, request.getPhoneNumber(), otp);
    sinpeExternalApiClient.editSubscription(request);
    return new CommandProcessingResultBuilder().withClientId(clientId).build();
  }

  /**
   * Deletes an active phone subscription through the provider.
   *
   * @param clientId client owning the subscription
   * @param phoneNumber linked phone number
   * @param otp submitted OTP
   * @return command result for the delete operation
   */
  @Override
  @Transactional
  public CommandProcessingResult deleteSubscription(
      final Long clientId,
      final String phoneNumber,
      final String otp) {
    context.authenticatedUser()
        .validateHasPermissionTo("DELETE_SINPE_ENROLLMENT");
    SinpeEnrollment enrollment = validateOtp(clientId, phoneNumber, otp);
    if (!enrollment.isLinked()) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.sinpe.subscription.not.found", "Subscription not found.");
    }
    String externalResponse =
        sinpeExternalApiClient.deleteSubscription(phoneNumber);
    if (StringUtils.isBlank(externalResponse)) {
      throw providerFailure();
    }
    enrollment.markAsUnlinked(LocalDateTime.now(ZoneOffset.UTC));
    enrollment.clearPendingOtp();
    enrollmentRepository.saveAndFlush(enrollment);

    Map<String, Object> changes = new HashMap<>();
    changes.put("phoneNumber", phoneNumber);

    return new CommandProcessingResultBuilder()
        .withClientId(clientId)
        .withEntityId(enrollment.getId())
        .with(changes)
        .build();
  }

  private SinpeEnrollment validateOtp(
      final Long clientId,
      final String mobileNumber,
      final String otp) {
    if (StringUtils.isBlank(otp)) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.sinpe.otp.required",
          "OTP is required for this operation.");
    }

    SinpeEnrollment enrollment =
        enrollmentRepository
            .findByClientIdAndMobileNumber(clientId, mobileNumber)
            .orElseThrow(
                () ->
                    new GeneralPlatformDomainRuleException(
                        "error.msg.sinpe.enrollment.not.found",
                        "No enrollment found for the given client and "
                            + "phone number."));

    LocalDateTime now = DateUtils.getLocalDateTimeOfTenant();
    if (!enrollment.isOtpValid(otp, now)) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.sinpe.otp.invalid", "Invalid or expired OTP.");
    }
    return enrollment;
  }

  private void validateSupportedIdentifier(
      final String phoneNumber,
      final String emailAddress,
      final String phoneFieldName) {
    boolean hasPhone = StringUtils.isNotBlank(phoneNumber);
    boolean hasEmail = StringUtils.isNotBlank(emailAddress);
    if (hasPhone == hasEmail) {
      throw new GeneralPlatformDomainRuleException(
          hasPhone
              ? "error.msg.sinpe.identifier.multiple"
              : "error.msg.sinpe.identifier.required",
          hasPhone
              ? "Supply either phoneNumber or emailAddress, not both."
              : phoneFieldName + " is required.");
    }
    if (hasEmail) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.sinpe.email.unsupported",
          "Email Fast Payment enrollment is not supported by the configured "
              + "SINPE provider.");
    }
    if (!phoneNumber.matches("\\d{8}")) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.sinpe.mobile.invalid",
          "Invalid SINPE Móvil phone number. Must be 8 digits.");
    }
  }

  private SavingsAccount validateSavingsAccount(
      final Long clientId,
      final String iban) {
    if (StringUtils.isBlank(iban)) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.sinpe.iban.required", "iban is required.");
    }
    ExternalId externalId = ExternalIdFactory.produce(iban);
    SavingsAccount savingsAccount =
        savingsAccountRepository.findByExternalId(externalId);
    if (savingsAccount == null) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.sinpe.savings.account.not.found",
          "Savings account not found for the supplied IBAN.");
    }
    if (!clientId.equals(savingsAccount.clientId())) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.sinpe.savings.account.not.owned.by.client",
          "Savings account does not belong to the supplied client.");
    }
    if (!savingsAccount.isActive()) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.sinpe.savings.account.inactive",
          "Savings account is inactive.");
    }
    if (savingsAccount.getExternalId() == null
        || !iban.equals(savingsAccount.getExternalId().getValue())) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.sinpe.iban.mismatch",
          "Savings account externalId does not match the supplied IBAN.");
    }
    return savingsAccount;
  }

  private void validateDuplicateLink(
      final SinpeEnrollment current,
      final String phoneNumber,
      final Long savingsAccountId) {
    enrollmentRepository
        .findFirstByMobileNumberAndStatus(phoneNumber, LINKED)
        .filter(existing -> !isSameEnrollment(existing, current))
        .ifPresent(
            existing -> {
              throw new GeneralPlatformDomainRuleException(
                  "error.msg.sinpe.identifier.already.linked",
                  "Phone number is already linked to a savings account.");
            });
    enrollmentRepository
        .findFirstBySavingsAccountIdAndStatus(savingsAccountId, LINKED)
        .filter(existing -> !isSameEnrollment(existing, current))
        .ifPresent(
            existing -> {
              throw new GeneralPlatformDomainRuleException(
                  "error.msg.sinpe.account.already.linked",
                  "Savings account is already linked to a phone number.");
            });
  }

  private boolean isSameEnrollment(
      final SinpeEnrollment existing,
      final SinpeEnrollment current) {
    return existing.getId() != null && existing.getId().equals(current.getId());
  }

  private GeneralPlatformDomainRuleException providerFailure() {
    return new GeneralPlatformDomainRuleException(
        "error.msg.sinpe.provider.failure",
        "SINPE provider did not confirm the operation.");
  }
}
