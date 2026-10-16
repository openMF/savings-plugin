package org.apache.fineract.baseteller.cashtransfer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.baseteller.service.CashExchangeInventory;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.teller.service.TellerWritePlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CashTransferAuthorizationTest {
  private AppUser actor;
  private JdbcTemplate jdbc;
  private CashExchangeInventory inventory;
  private TellerWritePlatformService commands;
  private CashTransferPlatformService service;

  @BeforeEach
  void setup() {
    ThreadLocalContextUtil.setBusinessDates(
        new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 10, 16))));
    actor = mock(AppUser.class);
    when(actor.getStaffId()).thenReturn(null);
    var security = mock(PlatformSecurityContext.class);
    when(security.authenticatedUser()).thenReturn(actor);
    jdbc = mock(JdbcTemplate.class);
    inventory = mock(CashExchangeInventory.class);
    commands = mock(TellerWritePlatformService.class);
    service =
        new CashTransferPlatformService(
            jdbc, security, inventory, commands, mock(FromJsonHelper.class));
  }

  @AfterEach
  void cleanup() {
    ThreadLocalContextUtil.reset();
  }

  @Test
  void permissionIsCheckedBeforeContextDisclosure() {
    doThrow(new SecurityException())
        .when(actor)
        .validateHasPermissionTo("READ_BASE_TELLER_CASH_TRANSFER");
    assertThrows(SecurityException.class, service::context);
    verifyNoInteractions(jdbc, inventory, commands);
  }

  @Test
  void missingOfficeFailsBeforeDatabaseRead() {
    assertThrows(RuntimeException.class, service::context);
    verifyNoInteractions(jdbc, inventory, commands);
  }

  @Test
  void unassignedUserCannotSelectAnotherSource() {
    var office = mock(Office.class);
    when(office.getHierarchy()).thenReturn(".1.");
    when(actor.getOffice()).thenReturn(office);
    assertThrows(RuntimeException.class, service::context);
    verifyNoInteractions(jdbc, inventory, commands);
  }

  @Test
  void createPermissionIsCheckedForPreview() {
    doThrow(new SecurityException())
        .when(actor)
        .validateHasPermissionTo("CREATE_BASE_TELLER_CASH_TRANSFER");
    assertThrows(SecurityException.class, () -> service.preview(null));
    verifyNoInteractions(jdbc, inventory, commands);
  }

  @Test
  void receiptRequiresReprintPermission() {
    doThrow(new SecurityException())
        .when(actor)
        .validateHasPermissionTo("REPRINT_BASE_TELLER_CASH_TRANSFER");
    assertThrows(SecurityException.class, () -> service.receipt(1L));
    verifyNoInteractions(jdbc, inventory, commands);
  }
}
