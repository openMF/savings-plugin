package org.apache.fineract.fastpayment.sinpe.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import org.apache.fineract.fastpayment.sinpe.data.SinpeSubscriptionRequest;
import org.apache.fineract.fastpayment.sinpe.domain.SinpeEnrollment;
import org.apache.fineract.fastpayment.sinpe.domain.SinpeEnrollmentRepository;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepository;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SinpeEnrollmentWritePlatformServiceImplTest {

  private static final Long CLIENT_ID = 11L;
  private static final Long USER_ID = 7L;
  private static final Long SAVINGS_ACCOUNT_ID = 31L;
  private static final String PHONE_NUMBER = "88887777";
  private static final String OTHER_PHONE_NUMBER = "88886666";
  private static final String IBAN = "CR05015202001026284066";
  private static final String OTP = "123456";
  private static final String PREVIOUS_OTP = "OLDOTP";
  private static final String PROVIDER_RESPONSE = "{\"ok\":true}";

  @Mock private PlatformSecurityContext context;
  @Mock private SinpeEnrollmentRepository enrollmentRepository;
  @Mock private ClientRepositoryWrapper clientRepository;
  @Mock private SavingsAccountRepository savingsAccountRepository;
  @Mock private SavingsSinpeExternalApiClient sinpeExternalApiClient;
  @Mock private AppUser user;
  @Mock private Client client;
  @Mock private SavingsAccount savingsAccount;

  private SinpeEnrollmentWritePlatformServiceImpl service;

  @BeforeEach
  void setUp() {
    ThreadLocalContextUtil.setTenant(
        new FineractPlatformTenant(1L, "default", "Default", "UTC", null));
    service =
        new SinpeEnrollmentWritePlatformServiceImpl(
            context,
            enrollmentRepository,
            clientRepository,
            savingsAccountRepository,
            sinpeExternalApiClient);
    when(context.authenticatedUser()).thenReturn(user);
  }

  @AfterEach
  void tearDown() {
    ThreadLocalContextUtil.clearTenant();
  }

  @Test
  void requestEnrollmentCreatesPhoneOtpAndReturnsIt() {
    when(clientRepository.findOneWithNotFoundDetection(CLIENT_ID)).thenReturn(client);
    when(enrollmentRepository.findByClientIdAndMobileNumberAndVerifiedTrue(CLIENT_ID, PHONE_NUMBER))
        .thenReturn(Optional.empty());
    when(enrollmentRepository.findByClientIdAndMobileNumber(CLIENT_ID, PHONE_NUMBER))
        .thenReturn(Optional.empty());
    when(user.getId()).thenReturn(USER_ID);

    var result = service.requestEnrollment(CLIENT_ID, PHONE_NUMBER);

    assertEquals(CLIENT_ID, result.getClientId());
    assertEquals(PHONE_NUMBER, result.getChanges().get("mobileNumber"));
    assertTrue(result.getChanges().containsKey("otp"));
    verify(clientRepository).findOneWithNotFoundDetection(CLIENT_ID);
    verify(enrollmentRepository).saveAndFlush(any(SinpeEnrollment.class));
  }

  @Test
  void createSubscriptionLinksVerifiedPhoneEnrollment() {
    SinpeEnrollment enrollment = verifiedEnrollment();
    when(enrollmentRepository.findByClientIdAndMobileNumber(CLIENT_ID, PHONE_NUMBER))
        .thenReturn(Optional.of(enrollment));
    when(clientRepository.findOneWithNotFoundDetection(CLIENT_ID)).thenReturn(client);
    mockValidSavingsAccount(CLIENT_ID, IBAN);
    when(savingsAccount.getId()).thenReturn(SAVINGS_ACCOUNT_ID);
    when(enrollmentRepository.findFirstByMobileNumberAndStatus(PHONE_NUMBER, "LINKED"))
        .thenReturn(Optional.empty());
    when(sinpeExternalApiClient.createSubscription(any())).thenReturn(PROVIDER_RESPONSE);

    var result = service.createSubscription(CLIENT_ID, PHONE_NUMBER, IBAN, OTP);

    assertTrue(enrollment.isLinked());
    assertEquals(SAVINGS_ACCOUNT_ID, enrollment.getSavingsAccountId());
    assertEquals(IBAN, enrollment.getIban());

    ArgumentCaptor<SinpeSubscriptionRequest> request =
        ArgumentCaptor.forClass(SinpeSubscriptionRequest.class);
    verify(sinpeExternalApiClient).createSubscription(request.capture());
    assertEquals(PHONE_NUMBER, request.getValue().getPhoneNumber());
    assertEquals(IBAN, request.getValue().getIban());
    assertEquals(OTP, request.getValue().getToken());
    assertFalse(result.getChanges().containsKey("externalResponse"));
    verify(enrollmentRepository).saveAndFlush(enrollment);
  }

  @Test
  void createSubscriptionRejectsSavingsAccountOwnedByAnotherClient() {
    SinpeEnrollment enrollment = verifiedEnrollment();
    when(enrollmentRepository.findByClientIdAndMobileNumber(CLIENT_ID, PHONE_NUMBER))
        .thenReturn(Optional.of(enrollment));
    when(clientRepository.findOneWithNotFoundDetection(CLIENT_ID)).thenReturn(client);
    when(savingsAccountRepository.findByExternalId(any())).thenReturn(savingsAccount);
    when(savingsAccount.clientId()).thenReturn(99L);

    GeneralPlatformDomainRuleException exception =
        assertThrows(
            GeneralPlatformDomainRuleException.class,
            () -> service.createSubscription(CLIENT_ID, PHONE_NUMBER, IBAN, OTP));

    assertEquals(
        "error.msg.sinpe.savings.account.not.owned.by.client",
        exception.getGlobalisationMessageCode());
    verify(sinpeExternalApiClient, never()).createSubscription(any());
  }

  @Test
  void createSubscriptionRejectsInactiveSavingsAccount() {
    SinpeEnrollment enrollment = verifiedEnrollment();
    when(enrollmentRepository.findByClientIdAndMobileNumber(CLIENT_ID, PHONE_NUMBER))
        .thenReturn(Optional.of(enrollment));
    when(clientRepository.findOneWithNotFoundDetection(CLIENT_ID)).thenReturn(client);
    when(savingsAccountRepository.findByExternalId(any())).thenReturn(savingsAccount);
    when(savingsAccount.clientId()).thenReturn(CLIENT_ID);
    when(savingsAccount.isActive()).thenReturn(false);

    GeneralPlatformDomainRuleException exception =
        assertThrows(
            GeneralPlatformDomainRuleException.class,
            () -> service.createSubscription(CLIENT_ID, PHONE_NUMBER, IBAN, OTP));

    assertEquals(
        "error.msg.sinpe.savings.account.inactive",
        exception.getGlobalisationMessageCode());
    verify(sinpeExternalApiClient, never()).createSubscription(any());
  }

  @Test
  void createSubscriptionRejectsExternalIdMismatch() {
    SinpeEnrollment enrollment = verifiedEnrollment();
    when(enrollmentRepository.findByClientIdAndMobileNumber(CLIENT_ID, PHONE_NUMBER))
        .thenReturn(Optional.of(enrollment));
    when(clientRepository.findOneWithNotFoundDetection(CLIENT_ID)).thenReturn(client);
    when(savingsAccountRepository.findByExternalId(any())).thenReturn(savingsAccount);
    when(savingsAccount.clientId()).thenReturn(CLIENT_ID);
    when(savingsAccount.isActive()).thenReturn(true);
    when(savingsAccount.getExternalId())
        .thenReturn(ExternalIdFactory.produce("CR99015202001026284066"));

    GeneralPlatformDomainRuleException exception =
        assertThrows(
            GeneralPlatformDomainRuleException.class,
            () -> service.createSubscription(CLIENT_ID, PHONE_NUMBER, IBAN, OTP));

    assertEquals("error.msg.sinpe.iban.mismatch", exception.getGlobalisationMessageCode());
    verify(sinpeExternalApiClient, never()).createSubscription(any());
  }

  @Test
  void createSubscriptionRejectsDuplicatePhoneLink() {
    SinpeEnrollment enrollment = verifiedEnrollment();
    SinpeEnrollment existing = verifiedEnrollment();
    existing.markAsLinked(99L, "CR00000000000000000000", LocalDateTime.now());
    when(enrollmentRepository.findByClientIdAndMobileNumber(CLIENT_ID, PHONE_NUMBER))
        .thenReturn(Optional.of(enrollment));
    when(clientRepository.findOneWithNotFoundDetection(CLIENT_ID)).thenReturn(client);
    mockValidSavingsAccount(CLIENT_ID, IBAN);
    when(enrollmentRepository.findFirstByMobileNumberAndStatus(PHONE_NUMBER, "LINKED"))
        .thenReturn(Optional.of(existing));

    GeneralPlatformDomainRuleException exception =
        assertThrows(
            GeneralPlatformDomainRuleException.class,
            () -> service.createSubscription(CLIENT_ID, PHONE_NUMBER, IBAN, OTP));

    assertEquals(
        "error.msg.sinpe.identifier.already.linked",
        exception.getGlobalisationMessageCode());
    verify(sinpeExternalApiClient, never()).createSubscription(any());
  }

  @Test
  void createSubscriptionAllowsDifferentPhoneLinkedToSameSavingsAccount() {
    SinpeEnrollment enrollment = verifiedEnrollment();
    SinpeEnrollment existing = verifiedEnrollment(OTHER_PHONE_NUMBER, OTP);
    existing.markAsLinked(SAVINGS_ACCOUNT_ID, IBAN, LocalDateTime.now());
    when(enrollmentRepository.findByClientIdAndMobileNumber(CLIENT_ID, PHONE_NUMBER))
        .thenReturn(Optional.of(enrollment));
    when(clientRepository.findOneWithNotFoundDetection(CLIENT_ID)).thenReturn(client);
    mockValidSavingsAccount(CLIENT_ID, IBAN);
    when(savingsAccount.getId()).thenReturn(SAVINGS_ACCOUNT_ID);
    when(enrollmentRepository.findFirstByMobileNumberAndStatus(PHONE_NUMBER, "LINKED"))
        .thenReturn(Optional.empty());
    lenient()
        .when(
            enrollmentRepository.findFirstBySavingsAccountIdAndStatus(
                SAVINGS_ACCOUNT_ID, "LINKED"))
        .thenReturn(Optional.of(existing));
    when(sinpeExternalApiClient.createSubscription(any())).thenReturn(PROVIDER_RESPONSE);

    service.createSubscription(CLIENT_ID, PHONE_NUMBER, IBAN, OTP);

    assertTrue(enrollment.isLinked());
    assertEquals(SAVINGS_ACCOUNT_ID, enrollment.getSavingsAccountId());
    assertEquals(OTHER_PHONE_NUMBER, existing.getMobileNumber());
    assertEquals(SAVINGS_ACCOUNT_ID, existing.getSavingsAccountId());
    verify(sinpeExternalApiClient).createSubscription(any());
    verify(enrollmentRepository).saveAndFlush(enrollment);
    verify(enrollmentRepository, never())
        .findFirstBySavingsAccountIdAndStatus(SAVINGS_ACCOUNT_ID, "LINKED");
  }

  @Test
  void createSubscriptionRejectsBothPhoneAndEmail() {
    GeneralPlatformDomainRuleException exception =
        assertThrows(
            GeneralPlatformDomainRuleException.class,
            () -> service.createSubscription(CLIENT_ID, PHONE_NUMBER, "a@example.com", IBAN, OTP));

    assertEquals("error.msg.sinpe.identifier.multiple", exception.getGlobalisationMessageCode());
  }

  @Test
  void createSubscriptionRejectsEmailWithoutProviderContract() {
    GeneralPlatformDomainRuleException exception =
        assertThrows(
            GeneralPlatformDomainRuleException.class,
            () -> service.createSubscription(CLIENT_ID, null, "a@example.com", IBAN, OTP));

    assertEquals("error.msg.sinpe.email.unsupported", exception.getGlobalisationMessageCode());
  }

  @Test
  void createSubscriptionRejectsBlankProviderResponse() {
    SinpeEnrollment enrollment = verifiedEnrollment();
    when(enrollmentRepository.findByClientIdAndMobileNumber(CLIENT_ID, PHONE_NUMBER))
        .thenReturn(Optional.of(enrollment));
    when(clientRepository.findOneWithNotFoundDetection(CLIENT_ID)).thenReturn(client);
    mockValidSavingsAccount(CLIENT_ID, IBAN);
    when(enrollmentRepository.findFirstByMobileNumberAndStatus(PHONE_NUMBER, "LINKED"))
        .thenReturn(Optional.empty());
    when(sinpeExternalApiClient.createSubscription(any())).thenReturn(null);

    GeneralPlatformDomainRuleException exception =
        assertThrows(
            GeneralPlatformDomainRuleException.class,
            () -> service.createSubscription(CLIENT_ID, PHONE_NUMBER, IBAN, OTP));

    assertEquals("error.msg.sinpe.provider.failure", exception.getGlobalisationMessageCode());
  }

  @Test
  void requestEnrollmentAllowsFreshOtpForLinkedPhone() {
    LocalDateTime linkedAt = LocalDateTime.now().minusDays(1);
    SinpeEnrollment enrollment = verifiedEnrollment(PREVIOUS_OTP);
    enrollment.markAsLinked(SAVINGS_ACCOUNT_ID, IBAN, linkedAt);
    when(clientRepository.findOneWithNotFoundDetection(CLIENT_ID)).thenReturn(client);
    when(enrollmentRepository.findByClientIdAndMobileNumberAndVerifiedTrue(CLIENT_ID, PHONE_NUMBER))
        .thenReturn(Optional.of(enrollment));
    when(enrollmentRepository.findByClientIdAndMobileNumber(CLIENT_ID, PHONE_NUMBER))
        .thenReturn(Optional.of(enrollment));

    service.requestEnrollment(CLIENT_ID, PHONE_NUMBER);

    assertEquals(SAVINGS_ACCOUNT_ID, enrollment.getSavingsAccountId());
    assertEquals(IBAN, enrollment.getIban());
    assertEquals("LINKED", enrollment.getStatus());
    assertEquals(linkedAt, enrollment.getLinkedOnUtc());
    assertFalse(enrollment.isOtpValid(PREVIOUS_OTP, LocalDateTime.now()));
    assertNotNull(enrollment.getPendingOtp());
    assertNotNull(enrollment.getOtpExpiry());
    assertFalse(enrollment.getPendingOtp().equals(PREVIOUS_OTP));
    ArgumentCaptor<SinpeEnrollment> saved = ArgumentCaptor.forClass(SinpeEnrollment.class);
    verify(enrollmentRepository).saveAndFlush(saved.capture());
    assertSame(enrollment, saved.getValue());
    verify(user, never()).getId();
  }

  @Test
  void requestEnrollmentAllowsFreshOtpForVerifiedPhoneWithExpiredOtp() {
    SinpeEnrollment enrollment = verifiedEnrollment(PREVIOUS_OTP);
    enrollment.setPendingOtp(PREVIOUS_OTP, LocalDateTime.of(2000, 1, 1, 0, 0));
    when(clientRepository.findOneWithNotFoundDetection(CLIENT_ID)).thenReturn(client);
    when(enrollmentRepository.findByClientIdAndMobileNumberAndVerifiedTrue(CLIENT_ID, PHONE_NUMBER))
        .thenReturn(Optional.of(enrollment));
    when(enrollmentRepository.findByClientIdAndMobileNumber(CLIENT_ID, PHONE_NUMBER))
        .thenReturn(Optional.of(enrollment));

    service.requestEnrollment(CLIENT_ID, PHONE_NUMBER);

    assertNotNull(enrollment.getPendingOtp());
    assertNotNull(enrollment.getOtpExpiry());
    assertFalse(enrollment.getPendingOtp().equals(PREVIOUS_OTP));
    verify(enrollmentRepository).saveAndFlush(enrollment);
  }

  @Test
  void requestEnrollmentReturnsExistingVerifiedPhoneWithValidOtp() {
    SinpeEnrollment enrollment = verifiedEnrollment();
    when(clientRepository.findOneWithNotFoundDetection(CLIENT_ID)).thenReturn(client);
    when(enrollmentRepository.findByClientIdAndMobileNumberAndVerifiedTrue(CLIENT_ID, PHONE_NUMBER))
        .thenReturn(Optional.of(enrollment));

    service.requestEnrollment(CLIENT_ID, PHONE_NUMBER);

    assertEquals(OTP, enrollment.getPendingOtp());
    verify(enrollmentRepository, never()).saveAndFlush(any());
  }

  @Test
  void deleteSubscriptionAcceptsFreshOtpAfterNewRequest() {
    SinpeEnrollment enrollment = verifiedEnrollment(PREVIOUS_OTP);
    enrollment.markAsLinked(SAVINGS_ACCOUNT_ID, IBAN, LocalDateTime.now());
    when(clientRepository.findOneWithNotFoundDetection(CLIENT_ID)).thenReturn(client);
    when(enrollmentRepository.findByClientIdAndMobileNumberAndVerifiedTrue(CLIENT_ID, PHONE_NUMBER))
        .thenReturn(Optional.of(enrollment));
    when(enrollmentRepository.findByClientIdAndMobileNumber(CLIENT_ID, PHONE_NUMBER))
        .thenReturn(Optional.of(enrollment));
    when(sinpeExternalApiClient.deleteSubscription(PHONE_NUMBER)).thenReturn(PROVIDER_RESPONSE);

    service.requestEnrollment(CLIENT_ID, PHONE_NUMBER);
    String freshOtp = enrollment.getPendingOtp();

    var result = service.deleteSubscription(CLIENT_ID, PHONE_NUMBER, freshOtp);

    assertEquals("UNLINKED", enrollment.getStatus());
    assertNull(enrollment.getPendingOtp());
    assertNull(enrollment.getOtpExpiry());
    assertFalse(result.getChanges().containsKey("externalResponse"));
    verify(sinpeExternalApiClient).deleteSubscription(PHONE_NUMBER);
    verify(enrollmentRepository, times(2)).saveAndFlush(enrollment);

    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> service.deleteSubscription(CLIENT_ID, PHONE_NUMBER, freshOtp));
    verify(sinpeExternalApiClient, times(1)).deleteSubscription(PHONE_NUMBER);
  }

  @Test
  void deleteSubscriptionRejectsPreviousOtpAfterFreshRequest() {
    SinpeEnrollment enrollment = verifiedEnrollment(PREVIOUS_OTP);
    enrollment.markAsLinked(SAVINGS_ACCOUNT_ID, IBAN, LocalDateTime.now());
    when(clientRepository.findOneWithNotFoundDetection(CLIENT_ID)).thenReturn(client);
    when(enrollmentRepository.findByClientIdAndMobileNumberAndVerifiedTrue(CLIENT_ID, PHONE_NUMBER))
        .thenReturn(Optional.of(enrollment));
    when(enrollmentRepository.findByClientIdAndMobileNumber(CLIENT_ID, PHONE_NUMBER))
        .thenReturn(Optional.of(enrollment));

    service.requestEnrollment(CLIENT_ID, PHONE_NUMBER);

    GeneralPlatformDomainRuleException exception =
        assertThrows(
            GeneralPlatformDomainRuleException.class,
            () -> service.deleteSubscription(CLIENT_ID, PHONE_NUMBER, PREVIOUS_OTP));

    assertEquals("error.msg.sinpe.otp.invalid", exception.getGlobalisationMessageCode());
    assertEquals("LINKED", enrollment.getStatus());
    verify(sinpeExternalApiClient, never()).deleteSubscription(PHONE_NUMBER);
  }

  @Test
  void deleteSubscriptionDoesNotUnlinkWhenProviderFails() {
    SinpeEnrollment enrollment = verifiedEnrollment();
    enrollment.markAsLinked(SAVINGS_ACCOUNT_ID, IBAN, LocalDateTime.now());
    when(enrollmentRepository.findByClientIdAndMobileNumber(CLIENT_ID, PHONE_NUMBER))
        .thenReturn(Optional.of(enrollment));
    when(sinpeExternalApiClient.deleteSubscription(PHONE_NUMBER)).thenReturn(null);

    GeneralPlatformDomainRuleException exception =
        assertThrows(
            GeneralPlatformDomainRuleException.class,
            () -> service.deleteSubscription(CLIENT_ID, PHONE_NUMBER, OTP));

    assertEquals("error.msg.sinpe.provider.failure", exception.getGlobalisationMessageCode());
    assertEquals("LINKED", enrollment.getStatus());
    verify(enrollmentRepository, never()).saveAndFlush(enrollment);
  }

  @Test
  void deleteSubscriptionMarksPhoneEnrollmentUnlinked() {
    SinpeEnrollment enrollment = verifiedEnrollment();
    enrollment.markAsLinked(SAVINGS_ACCOUNT_ID, IBAN, LocalDateTime.now());
    when(enrollmentRepository.findByClientIdAndMobileNumber(CLIENT_ID, PHONE_NUMBER))
        .thenReturn(Optional.of(enrollment));
    when(sinpeExternalApiClient.deleteSubscription(PHONE_NUMBER)).thenReturn(PROVIDER_RESPONSE);

    service.deleteSubscription(CLIENT_ID, PHONE_NUMBER, OTP);

    assertEquals("UNLINKED", enrollment.getStatus());
    verify(enrollmentRepository).saveAndFlush(enrollment);
  }

  @Test
  void requestEnrollmentRejectsBothPhoneAndEmail() {
    GeneralPlatformDomainRuleException exception =
        assertThrows(
            GeneralPlatformDomainRuleException.class,
            () -> service.requestEnrollment(CLIENT_ID, PHONE_NUMBER, "a@example.com"));

    assertEquals("error.msg.sinpe.identifier.multiple", exception.getGlobalisationMessageCode());
  }

  private SinpeEnrollment verifiedEnrollment() {
    return verifiedEnrollment(OTP);
  }

  private SinpeEnrollment verifiedEnrollment(String otp) {
    return verifiedEnrollment(PHONE_NUMBER, otp);
  }

  private SinpeEnrollment verifiedEnrollment(String mobileNumber, String otp) {
    SinpeEnrollment enrollment = new SinpeEnrollment(CLIENT_ID, mobileNumber, USER_ID);
    enrollment.setPendingOtp(otp, LocalDateTime.now().plusMinutes(5));
    return enrollment;
  }

  private void mockValidSavingsAccount(Long ownerClientId, String externalIdValue) {
    ExternalId externalId = ExternalIdFactory.produce(externalIdValue);
    when(savingsAccountRepository.findByExternalId(any())).thenReturn(savingsAccount);
    when(savingsAccount.clientId()).thenReturn(ownerClientId);
    when(savingsAccount.isActive()).thenReturn(true);
    when(savingsAccount.getExternalId()).thenReturn(externalId);
  }
}
