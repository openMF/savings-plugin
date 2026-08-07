package org.apache.fineract.fastpayment.sinpe.service;

import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.apache.fineract.fastpayment.sinpe.data.SinpeLinkedPhoneData;
import org.apache.fineract.fastpayment.sinpe.domain.SinpeEnrollment;
import org.apache.fineract.fastpayment.sinpe.domain.SinpeEnrollmentRepository;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SinpeEnrollmentReadPlatformServiceImplTest {

  private static final Long CLIENT_ID = 11L;
  private static final Long SAVINGS_ACCOUNT_ID = 31L;
  private static final Long USER_ID = 7L;
  private static final String IBAN = "CR05015202001026284066";
  private static final String MASKED_IBAN = "****4066";

  @Mock private PlatformSecurityContext context;
  @Mock private SinpeEnrollmentRepository enrollmentRepository;
  @Mock private SavingsSinpeExternalApiClient sinpeExternalApiClient;
  @Mock private SavingsAccountReadPlatformService savingsAccountReadPlatformService;
  @Mock private AppUser user;

  private SinpeEnrollmentReadPlatformServiceImpl service;

  @BeforeEach
  void setUp() {
    service =
        new SinpeEnrollmentReadPlatformServiceImpl(
            context,
            enrollmentRepository,
            sinpeExternalApiClient,
            savingsAccountReadPlatformService);
  }

  @Test
  void retrieveLinkedPhonesReturnsActiveLinksForSavingsAccount() {
    when(context.authenticatedUser()).thenReturn(user);
    when(enrollmentRepository.findBySavingsAccountIdAndStatusOrderByMobileNumberAsc(
            SAVINGS_ACCOUNT_ID, "LINKED"))
        .thenReturn(
            List.of(linkedEnrollment("88886666", IBAN), linkedEnrollment("88887777", IBAN)));

    Collection<SinpeLinkedPhoneData> result = service.retrieveLinkedPhones(SAVINGS_ACCOUNT_ID);

    assertEquals(2, result.size());
    List<SinpeLinkedPhoneData> linkedPhones = List.copyOf(result);
    assertEquals(SAVINGS_ACCOUNT_ID, linkedPhones.get(0).getSavingsAccountId());
    assertEquals(MASKED_IBAN, linkedPhones.get(0).getMaskedIban());
    assertEquals("88886666", linkedPhones.get(0).getMobileNumber());
    assertEquals("LINKED", linkedPhones.get(0).getStatus());
    assertEquals("88887777", linkedPhones.get(1).getMobileNumber());
    verify(user).validateHasPermissionTo("READ_SINPE_ENROLLMENT");
    verify(user).validateHasReadPermission("savingsaccount");
    verify(savingsAccountReadPlatformService).retrieveOne(SAVINGS_ACCOUNT_ID);
  }

  @Test
  void retrieveLinkedPhonesDoesNotQueryLinksWithoutSavingsAccountReadAccess() {
    RuntimeException accessDenied = new RuntimeException("access denied");
    when(context.authenticatedUser()).thenReturn(user);
    doThrow(accessDenied).when(user).validateHasReadPermission("savingsaccount");

    RuntimeException result =
        assertThrows(
            RuntimeException.class, () -> service.retrieveLinkedPhones(SAVINGS_ACCOUNT_ID));

    assertEquals(accessDenied, result);
    verify(savingsAccountReadPlatformService, never()).retrieveOne(SAVINGS_ACCOUNT_ID);
    verify(enrollmentRepository, never())
        .findBySavingsAccountIdAndStatusOrderByMobileNumberAsc(SAVINGS_ACCOUNT_ID, "LINKED");
  }

  @Test
  void retrieveLinkedPhonesDoesNotQueryLinksWhenSavingsAccountCannotBeRead() {
    RuntimeException notFound = new RuntimeException("not found");
    when(context.authenticatedUser()).thenReturn(user);
    when(savingsAccountReadPlatformService.retrieveOne(SAVINGS_ACCOUNT_ID)).thenThrow(notFound);

    RuntimeException result =
        assertThrows(
            RuntimeException.class, () -> service.retrieveLinkedPhones(SAVINGS_ACCOUNT_ID));

    assertEquals(notFound, result);
    verify(enrollmentRepository, never())
        .findBySavingsAccountIdAndStatusOrderByMobileNumberAsc(SAVINGS_ACCOUNT_ID, "LINKED");
  }

  @Test
  void retrieveLinkedPhonesReturnsEmptyWhenNoLinksExist() {
    when(context.authenticatedUser()).thenReturn(user);
    when(enrollmentRepository.findBySavingsAccountIdAndStatusOrderByMobileNumberAsc(
            SAVINGS_ACCOUNT_ID, "LINKED"))
        .thenReturn(List.of());

    Collection<SinpeLinkedPhoneData> result = service.retrieveLinkedPhones(SAVINGS_ACCOUNT_ID);

    assertTrue(result.isEmpty());
  }

  @Test
  void retrieveLinkedPhonesRequestsOnlyLinkedRows() {
    when(context.authenticatedUser()).thenReturn(user);
    List<SinpeEnrollment> enrollments =
        List.of(
            linkedEnrollment("88887777", IBAN),
            unlinkedEnrollment("88886666", IBAN),
            pendingEnrollment("88885555"));
    when(enrollmentRepository.findBySavingsAccountIdAndStatusOrderByMobileNumberAsc(
            SAVINGS_ACCOUNT_ID, "LINKED"))
        .thenAnswer(
            invocation ->
                enrollments.stream()
                    .filter(enrollment -> invocation.getArgument(1).equals(enrollment.getStatus()))
                    .toList());

    Collection<SinpeLinkedPhoneData> result = service.retrieveLinkedPhones(SAVINGS_ACCOUNT_ID);

    assertEquals(1, result.size());
    assertEquals("88887777", result.iterator().next().getMobileNumber());
    verify(enrollmentRepository)
        .findBySavingsAccountIdAndStatusOrderByMobileNumberAsc(SAVINGS_ACCOUNT_ID, "LINKED");
  }

  @Test
  void retrieveLinkedPhonesDoesNotExposeShortIbanValues() {
    when(context.authenticatedUser()).thenReturn(user);
    when(enrollmentRepository.findBySavingsAccountIdAndStatusOrderByMobileNumberAsc(
            SAVINGS_ACCOUNT_ID, "LINKED"))
        .thenReturn(List.of(linkedEnrollment("88887777", "1234")));

    Collection<SinpeLinkedPhoneData> result = service.retrieveLinkedPhones(SAVINGS_ACCOUNT_ID);

    assertEquals("****", result.iterator().next().getMaskedIban());
  }

  @Test
  void linkedPhoneResponseDataExposesOnlySafeFields() {
    Set<String> fields =
        List.of(SinpeLinkedPhoneData.class.getDeclaredFields()).stream()
            .map(Field::getName)
            .collect(toSet());

    assertEquals(Set.of("savingsAccountId", "maskedIban", "mobileNumber", "status"), fields);
  }

  private SinpeEnrollment linkedEnrollment(final String mobileNumber, final String iban) {
    SinpeEnrollment enrollment = new SinpeEnrollment(CLIENT_ID, mobileNumber, USER_ID);
    enrollment.markAsLinked(SAVINGS_ACCOUNT_ID, iban, LocalDateTime.now());
    return enrollment;
  }

  private SinpeEnrollment unlinkedEnrollment(final String mobileNumber, final String iban) {
    SinpeEnrollment enrollment = linkedEnrollment(mobileNumber, iban);
    enrollment.markAsUnlinked(LocalDateTime.now());
    return enrollment;
  }

  private SinpeEnrollment pendingEnrollment(final String mobileNumber) {
    return new SinpeEnrollment(CLIENT_ID, mobileNumber, USER_ID);
  }
}
