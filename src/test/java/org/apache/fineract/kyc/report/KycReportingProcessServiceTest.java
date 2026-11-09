/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.kyc.report;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MultivaluedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.client.data.ClientData;
import org.apache.fineract.portfolio.client.exception.ClientNotFoundException;
import org.apache.fineract.portfolio.client.service.ClientIdentifierReadPlatformService;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class KycReportingProcessServiceTest {
  private final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
  private final AppUser user = mock(AppUser.class);
  private final ClientReadPlatformService clients = mock(ClientReadPlatformService.class);
  private final ClientIdentifierReadPlatformService identities =
      mock(ClientIdentifierReadPlatformService.class);
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final KycPdfRenderer renderer = mock(KycPdfRenderer.class);
  private final KycReportingProcessService service =
      new KycReportingProcessService(context, clients, identities, jdbc, renderer);

  private MultivaluedHashMap<String, String> params(String id) {
    var params = new MultivaluedHashMap<String, String>();
    params.add("R_clientId", id);
    params.add("output-type", "PDF");
    return params;
  }

  @BeforeEach
  void setup() {
    when(context.authenticatedUser()).thenReturn(user);
  }

  @Test
  void additionalProviderPreservesCoreSingleServiceInjection() {
    try (var spring =
        new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
      var core =
          mock(org.apache.fineract.infrastructure.report.service.ReportingProcessService.class);
      spring.registerBean(
          "datatableReportingProcessService",
          org.apache.fineract.infrastructure.report.service.ReportingProcessService.class,
          () -> core);
      spring.registerBean(PlatformSecurityContext.class, () -> context);
      spring.registerBean(ClientReadPlatformService.class, () -> clients);
      spring.registerBean(ClientIdentifierReadPlatformService.class, () -> identities);
      spring.registerBean(JdbcTemplate.class, () -> jdbc);
      spring.registerBean(KycPdfRenderer.class, () -> renderer);
      spring.register(KycReportingProcessService.class);
      spring.refresh();
      assertSame(
          core,
          spring.getBean(
              org.apache.fineract.infrastructure.report.service.ReportingProcessService.class));
      assertEquals(
          2,
          spring
              .getBeansOfType(
                  org.apache.fineract.infrastructure.report.service.ReportingProcessService.class)
              .size());
    }
  }

  @Test
  void rejectsInvalidClientIdsBeforeDataAccess() {
    for (String id : List.of("0", "-1", "1 OR 1=1", "1.1", "9223372036854775808", "", "01")) {
      assertThrows(
          BadRequestException.class, () -> service.processRequest("KYCNaturalPerson", params(id)));
    }
    verifyNoInteractions(clients, jdbc, renderer);
  }

  @Test
  void rejectsDuplicateIdsAndUnknownParameters() {
    var p = params("1");
    p.add("R_clientId", "2");
    assertThrows(BadRequestException.class, () -> service.getReportParams("KYCNaturalPerson", p));
    var unknown = params("1");
    unknown.add("R_sql", "select 1");
    assertThrows(
        BadRequestException.class, () -> service.getReportParams("KYCNaturalPerson", unknown));
  }

  @Test
  void rejectsNonPdfAndUnknownReport() {
    var p = params("1");
    p.putSingle("output-type", "HTML");
    assertThrows(BadRequestException.class, () -> service.getReportParams("KYCNaturalPerson", p));
    assertThrows(NotFoundException.class, () -> service.getReportParams("Other", params("1")));
  }

  @Test
  void deniesMissingReportPermissionBeforeReadingClient() {
    when(user.hasNotPermissionForReport("KYCNaturalPerson")).thenReturn(true);
    assertThrows(
        NoAuthorizationException.class,
        () -> service.processRequest("KYCNaturalPerson", params("1")));
    verifyNoInteractions(clients, identities, jdbc, renderer);
  }

  @Test
  void deniesMissingClientPermissionBeforeReadingClient() {
    doThrow(new NoAuthorizationException("denied")).when(user).validateHasReadPermission("CLIENT");
    assertThrows(
        NoAuthorizationException.class,
        () -> service.processRequest("KYCNaturalPerson", params("1")));
    verifyNoInteractions(clients, identities, jdbc, renderer);
  }

  @Test
  void inaccessibleOfficeOrTenantCannotReadKycData() {
    when(clients.retrieveOne(1L)).thenThrow(new ClientNotFoundException(1L));
    assertThrows(
        ClientNotFoundException.class,
        () -> service.processRequest("KYCNaturalPerson", params("1")));
    verifyNoInteractions(identities, jdbc, renderer);
  }

  @Test
  void rejectsWrongLegalForm() {
    ClientData client = new ClientData();
    client.setLegalForm(new EnumOptionData(2L, "clientLegalFormType.entity", "Entity"));
    when(clients.retrieveOne(1L)).thenReturn(client);
    assertThrows(
        BadRequestException.class, () -> service.processRequest("KYCNaturalPerson", params("1")));
    verifyNoInteractions(identities, jdbc, renderer);
  }

  @Test
  void restrictedSectionsAreNotReadAndPdfIsNeverCached() {
    ClientData client = new ClientData();
    client.setId(1L);
    client.setLegalForm(new EnumOptionData(1L, "clientLegalFormType.person", "Person"));
    when(clients.retrieveOne(1L)).thenReturn(client);
    when(user.hasNotPermissionForAnyOf(any(String[].class))).thenReturn(true);
    when(user.hasNotPermissionForDatatable("dt_kyc_validation", "READ")).thenReturn(true);
    when(renderer.render(eq(client), isNull(), eq(Map.of()), eq(Map.of()), eq(false)))
        .thenReturn(new byte[] {1, 2});
    try (var response = service.processRequest("KYCNaturalPerson", params("1"))) {
      assertEquals(200, response.getStatus());
      assertEquals("application/pdf", response.getMediaType().toString());
      assertEquals("no-store, private", response.getHeaderString("Cache-Control"));
      assertTrue(response.getHeaderString("Content-Disposition").contains("KYC-1.pdf"));
      assertArrayEquals(new byte[] {1, 2}, (byte[]) response.getEntity());
    }
    verifyNoInteractions(identities);
    verify(jdbc, never()).queryForObject(anyString(), eq(Integer.class), anyString());
  }

  @Test
  void registeredDatatableUsesBoundClientIdAndEntityTable() {
    ClientData client = new ClientData();
    client.setLegalForm(new EnumOptionData(2L, "clientLegalFormType.entity", "Entity"));
    when(clients.retrieveOne(7L)).thenReturn(client);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("dt_kyc_validation_entity")))
        .thenReturn(1);
    Map<String, Object> row = Map.of("validation_status", "PENDING");
    when(jdbc.queryForList("SELECT * FROM dt_kyc_validation_entity WHERE client_id = ?", 7L))
        .thenReturn(List.of(row));
    when(renderer.render(eq(client), anyList(), eq(Map.of()), eq(row), eq(true)))
        .thenReturn(new byte[] {1});
    service.processRequest("KYCLegalPerson", params("7")).close();
    verify(clients).retrieveOne(7L);
    verify(identities).retrieveClientIdentifiers(7L);
    verify(renderer).render(eq(client), anyList(), eq(Map.of()), eq(row), eq(true));
  }
}
