package org.apache.fineract.fastpayment.sinpe.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.fineract.fastpayment.sinpe.data.SinpeLinkedPhoneData;
import org.apache.fineract.fastpayment.sinpe.data.SinpePhoneStatusData;
import org.apache.fineract.fastpayment.sinpe.service.SinpeEnrollmentReadPlatformService;
import org.apache.fineract.fastpayment.sinpe.service.SinpeEnrollmentWritePlatformService;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SinpeEnrollmentApiResourceTest {

  private static final Long SAVINGS_ACCOUNT_ID = 31L;

  @Mock private PlatformSecurityContext context;
  @Mock private SinpeEnrollmentWritePlatformService writePlatformService;
  @Mock private SinpeEnrollmentReadPlatformService readPlatformService;
  @Mock private DefaultToApiJsonSerializer<CommandProcessingResult> commandSerializer;
  @Mock private DefaultToApiJsonSerializer<SinpePhoneStatusData> phoneStatusSerializer;
  @Mock private DefaultToApiJsonSerializer<SinpeLinkedPhoneData> linkedPhoneSerializer;

  private SinpeEnrollmentApiResource resource;

  @BeforeEach
  void setUp() {
    resource =
        new SinpeEnrollmentApiResource(
            context,
            commandSerializer,
            writePlatformService,
            readPlatformService,
            phoneStatusSerializer,
            linkedPhoneSerializer);
  }

  @Test
  void retrieveLinkedPhonesRequiresReadPermissionAndSerializesResult() {
    AppUser user = mock(AppUser.class);
    List<SinpeLinkedPhoneData> linkedPhones =
        List.of(
            SinpeLinkedPhoneData.builder()
                .savingsAccountId(SAVINGS_ACCOUNT_ID)
                .maskedIban("****4066")
                .mobileNumber("88887777")
                .status("LINKED")
                .build());
    when(context.authenticatedUser()).thenReturn(user);
    when(readPlatformService.retrieveLinkedPhones(SAVINGS_ACCOUNT_ID)).thenReturn(linkedPhones);
    when(linkedPhoneSerializer.serializeResult(linkedPhones)).thenReturn("[]");

    String result = resource.retrieveLinkedPhones(SAVINGS_ACCOUNT_ID);

    assertEquals("[]", result);
    verify(user).validateHasPermissionTo("READ_SINPE_ENROLLMENT");
    verify(readPlatformService).retrieveLinkedPhones(SAVINGS_ACCOUNT_ID);
    verify(linkedPhoneSerializer).serializeResult(linkedPhones);
  }
}
