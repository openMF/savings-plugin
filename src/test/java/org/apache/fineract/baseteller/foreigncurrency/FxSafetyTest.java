package org.apache.fineract.baseteller.foreigncurrency;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.apache.fineract.baseteller.service.CashExchangeInventory;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FxSafetyTest {
  @Test
  void executionAndPublicationCannotReachAnyDatabaseOrFinancialCollaborator() {
    var security = mock(PlatformSecurityContext.class);
    var actor = mock(AppUser.class);
    var office = mock(Office.class);
    when(security.authenticatedUser()).thenReturn(actor);
    when(actor.getOffice()).thenReturn(office);
    when(office.getHierarchy()).thenReturn(".1.");
    var jdbc = mock(JdbcTemplate.class);
    var rates = new FxRateService(security, jdbc);
    var inventory = mock(CashExchangeInventory.class);
    var clients = mock(ClientReadPlatformService.class);
    var teller = new FxTellerService(security, jdbc, rates, inventory, clients);
    for (var op : FxData.Operation.values()) {
      var error = assertThrows(GeneralPlatformDomainRuleException.class, () -> teller.confirm(op));
      assertTrue(error.getMessage().contains("disabled"));
      verify(actor).validateHasPermissionTo(FxTellerService.permission(op));
    }
    assertThrows(GeneralPlatformDomainRuleException.class, rates::publish);
    verify(actor).validateHasPermissionTo("PUBLISH_BASE_TELLER_FX_RATE");
    verifyNoInteractions(jdbc, inventory, clients);
  }

  @Test
  void unauthorizedAdministratorsAndTellersAreRejectedBeforeReadingState() {
    var security = mock(PlatformSecurityContext.class);
    var actor = mock(AppUser.class);
    when(security.authenticatedUser()).thenReturn(actor);
    doThrow(new org.springframework.security.access.AccessDeniedException("denied"))
        .when(actor)
        .validateHasPermissionTo(anyString());
    var jdbc = mock(JdbcTemplate.class);
    var rates = new FxRateService(security, jdbc);
    var inventory = mock(CashExchangeInventory.class);
    var clients = mock(ClientReadPlatformService.class);
    var teller = new FxTellerService(security, jdbc, rates, inventory, clients);
    assertThrows(org.springframework.security.access.AccessDeniedException.class, rates::context);
    assertThrows(
        org.springframework.security.access.AccessDeniedException.class, () -> rates.preview(null));
    assertThrows(
        org.springframework.security.access.AccessDeniedException.class, () -> rates.save(null));
    assertThrows(org.springframework.security.access.AccessDeniedException.class, teller::context);
    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> teller.preview(FxData.Operation.SALE, null));
    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> teller.confirm(FxData.Operation.PURCHASE));
    verifyNoInteractions(jdbc, inventory, clients);
  }
}
