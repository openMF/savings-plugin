package org.apache.fineract.baseteller.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.fineract.baseteller.data.CashInventoryContextData;
import org.apache.fineract.baseteller.data.CashInventoryData;
import org.apache.fineract.baseteller.service.CashInventoryReadPlatformService;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.Test;

class CashInventoryApiResourceTest {

  @Test
  void contextRequiresDedicatedPermissionAndReturnsServiceValue() {
    final Fixture fixture = fixture();
    final CashInventoryContextData expected =
        new CashInventoryContextData(List.of(), List.of(), List.of());
    when(fixture.read.context()).thenReturn(expected);

    assertSame(expected, fixture.resource.inventoryContext());
    verify(fixture.user).validateHasPermissionTo("READ_BASE_TELLER_CASH_INVENTORY");
  }

  @Test
  void allFiltersOmittedMeansAllAuthorizedInventory() {
    final Fixture fixture = fixture();
    final List<CashInventoryData> expected = List.of();
    when(fixture.read.inventory(null, null, null, false)).thenReturn(expected);

    assertSame(expected, fixture.resource.inventory(null, null, null, null));
    verify(fixture.read).inventory(null, null, null, false);
  }

  @Test
  void delegatesCashCurrencyAndSpecificCustodianFilters() {
    final Fixture fixture = fixture();

    fixture.resource.inventory("TELLER:7", "CASH", "MXN", "false");

    verify(fixture.read).inventory("TELLER:7", "CASH", "MXN", false);
  }

  @Test
  void delegatesCheckAndShowLastCutoffFilters() {
    final Fixture fixture = fixture();

    fixture.resource.inventory("TELLER:7", "CHECK", "USD", "true");

    verify(fixture.read).inventory("TELLER:7", "CHECK", "USD", true);
  }

  @Test
  void booleanParserAcceptsOnlyExplicitBooleanValues() {
    assertTrue(CashInventoryApiResource.parseBoolean("true"));
    assertTrue(CashInventoryApiResource.parseBoolean("TRUE"));
    assertFalse(CashInventoryApiResource.parseBoolean("false"));
    assertFalse(CashInventoryApiResource.parseBoolean(null));
    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> CashInventoryApiResource.parseBoolean("yes"));
  }

  private static Fixture fixture() {
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    final AppUser user = mock(AppUser.class);
    final CashInventoryReadPlatformService read = mock(CashInventoryReadPlatformService.class);
    when(context.authenticatedUser()).thenReturn(user);
    return new Fixture(user, read, new CashInventoryApiResource(context, read));
  }

  private record Fixture(
      AppUser user,
      CashInventoryReadPlatformService read,
      CashInventoryApiResource resource) {}
}
