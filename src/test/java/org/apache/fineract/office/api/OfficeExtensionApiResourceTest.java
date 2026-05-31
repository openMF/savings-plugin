/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.office.api;

import org.apache.fineract.office.api.OfficeExtensionApiResource;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.office.data.OfficeGeolocationData;
import org.apache.fineract.office.data.OfficeServiceData;
import org.apache.fineract.office.service.OfficeExtensionReadPlatformService;
import org.apache.fineract.office.service.OfficeExtensionWritePlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OfficeExtensionApiResourceTest {

  @Mock private PlatformSecurityContext context;
  @Mock private OfficeExtensionReadPlatformService readService;
  @Mock private OfficeExtensionWritePlatformService writeService;
  @Mock private DefaultToApiJsonSerializer<OfficeServiceData> serviceSerializer;
  @Mock private DefaultToApiJsonSerializer<OfficeGeolocationData> geolocationSerializer;

  private OfficeExtensionApiResource resource;

  private static final Long OFFICE_ID = 1L;
  private static final Long SERVICE_ID = 10L;

  @BeforeEach
  void setUp() {
    resource =
        new OfficeExtensionApiResource(
            context, readService, writeService, serviceSerializer, geolocationSerializer);
  }

  private void mockAuthenticatedUser() {
    AppUser user = mock(AppUser.class);
    when(context.authenticatedUser()).thenReturn(user);
  }

  @Test
  void retrieveOfficeServices_returnsData() {
    mockAuthenticatedUser();
    List<OfficeServiceData> data =
        List.of(
            OfficeServiceData.instance(
                SERVICE_ID, OFFICE_ID, "Account Opening", "SVC-001", "Mon-Fri 09:00-17:00"));
    when(readService.retrieveOfficeServices(OFFICE_ID)).thenReturn(data);
    when(serviceSerializer.serializeResult(data)).thenReturn("[]");

    String result = resource.retrieveOfficeServices(OFFICE_ID);

    assertNotNull(result);
    verify(readService).retrieveOfficeServices(OFFICE_ID);
  }

  @Test
  void createOfficeService_delegatesToWriteService() {
    mockAuthenticatedUser();
    CommandProcessingResult cpr =
        new CommandProcessingResultBuilder().withEntityId(SERVICE_ID).build();
    String jsonBody = "{\"serviceName\":\"Loans\",\"workingHours\":\"Mon-Fri\"}";
    when(writeService.createOfficeService(OFFICE_ID, jsonBody)).thenReturn(cpr);
    when(serviceSerializer.serializeResult(cpr)).thenReturn("{}");

    String result = resource.createOfficeService(OFFICE_ID, jsonBody);

    assertNotNull(result);
    verify(writeService).createOfficeService(OFFICE_ID, jsonBody);
  }

  @Test
  void updateOfficeService_passesOfficeIdToWriteService() {
    mockAuthenticatedUser();
    CommandProcessingResult cpr =
        new CommandProcessingResultBuilder().withEntityId(SERVICE_ID).build();
    String jsonBody = "{\"serviceName\":\"Updated\"}";
    when(writeService.updateOfficeService(OFFICE_ID, SERVICE_ID, jsonBody)).thenReturn(cpr);
    when(serviceSerializer.serializeResult(cpr)).thenReturn("{}");

    String result = resource.updateOfficeService(OFFICE_ID, SERVICE_ID, jsonBody);

    assertNotNull(result);
    verify(writeService).updateOfficeService(OFFICE_ID, SERVICE_ID, jsonBody);
  }

  @Test
  void deleteOfficeService_passesOfficeIdToWriteService() {
    mockAuthenticatedUser();
    CommandProcessingResult cpr =
        new CommandProcessingResultBuilder().withEntityId(SERVICE_ID).build();
    when(writeService.deleteOfficeService(OFFICE_ID, SERVICE_ID)).thenReturn(cpr);
    when(serviceSerializer.serializeResult(cpr)).thenReturn("{}");

    String result = resource.deleteOfficeService(OFFICE_ID, SERVICE_ID);

    assertNotNull(result);
    verify(writeService).deleteOfficeService(OFFICE_ID, SERVICE_ID);
  }

  @Test
  void retrieveOfficeGeolocation_returnsData() {
    mockAuthenticatedUser();
    OfficeGeolocationData data =
        OfficeGeolocationData.instance(
            1L, OFFICE_ID, new BigDecimal("19.4326077"), new BigDecimal("-99.1332080"));
    when(readService.retrieveOfficeGeolocation(OFFICE_ID)).thenReturn(data);
    when(geolocationSerializer.serializeResult(data)).thenReturn("{}");

    String result = resource.retrieveOfficeGeolocation(OFFICE_ID);

    assertNotNull(result);
    verify(readService).retrieveOfficeGeolocation(OFFICE_ID);
  }

  @Test
  void saveOfficeGeolocation_delegatesToWriteService() {
    mockAuthenticatedUser();
    CommandProcessingResult cpr =
        new CommandProcessingResultBuilder().withEntityId(1L).withOfficeId(OFFICE_ID).build();
    String jsonBody = "{\"latitude\":\"19.43\",\"longitude\":\"-99.13\",\"locale\":\"en\"}";
    when(writeService.saveOfficeGeolocation(OFFICE_ID, jsonBody)).thenReturn(cpr);
    when(geolocationSerializer.serializeResult(cpr)).thenReturn("{}");

    String result = resource.saveOfficeGeolocation(OFFICE_ID, jsonBody);

    assertNotNull(result);
    verify(writeService).saveOfficeGeolocation(OFFICE_ID, jsonBody);
  }

  @Test
  void deleteOfficeGeolocation_delegatesToWriteService() {
    mockAuthenticatedUser();
    CommandProcessingResult cpr =
        new CommandProcessingResultBuilder().withOfficeId(OFFICE_ID).build();
    when(writeService.deleteOfficeGeolocation(OFFICE_ID)).thenReturn(cpr);
    when(geolocationSerializer.serializeResult(any())).thenReturn("{}");

    String result = resource.deleteOfficeGeolocation(OFFICE_ID);

    assertNotNull(result);
    verify(writeService).deleteOfficeGeolocation(OFFICE_ID);
  }
}
