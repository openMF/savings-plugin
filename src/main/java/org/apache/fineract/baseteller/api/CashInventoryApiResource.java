package org.apache.fineract.baseteller.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.baseteller.data.CashInventoryContextData;
import org.apache.fineract.baseteller.data.CashInventoryData;
import org.apache.fineract.baseteller.service.CashInventoryReadPlatformService;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Component;

@Path("/v2/base-teller/cash-inventory")
@Component
@Produces(MediaType.APPLICATION_JSON)
@Tag(
    name = "Base Teller Cash Inventory",
    description = "Real-time cash and check inventory under authorized custody")
@RequiredArgsConstructor
public class CashInventoryApiResource {

  private static final String PERMISSION = "READ_BASE_TELLER_CASH_INVENTORY";

  private final PlatformSecurityContext context;
  private final CashInventoryReadPlatformService readService;

  @GET
  @Path("/context")
  @Operation(
      summary = "Retrieve inventory filters",
      description = "Returns only custodians allowed by the authenticated user's office and cashier scope.")
  public CashInventoryContextData inventoryContext() {
    context.authenticatedUser().validateHasPermissionTo(PERMISSION);
    return readService.context();
  }

  @GET
  @Operation(
      summary = "Retrieve cash and check inventory",
      description =
          "Omitted filters mean all authorized values. Balances use the current Fineract business date."
              + " showLastCutOff controls cutoff metadata visibility; cutoff amounts are always applied to balance.")
  public List<CashInventoryData> inventory(
      @Parameter(description = "Stable key returned by the context endpoint")
          @QueryParam("custodianKey")
          final String custodianKey,
      @Parameter(description = "CASH or CHECK") @QueryParam("transactionType")
          final String transactionType,
      @Parameter(description = "Enabled ISO currency code") @QueryParam("currencyCode")
          final String currencyCode,
      @Parameter(description = "Expose the most recent cutoff timestamp and amount")
          @QueryParam("showLastCutOff")
          final String showLastCutOff) {
    context.authenticatedUser().validateHasPermissionTo(PERMISSION);
    return readService.inventory(
        custodianKey,
        transactionType,
        currencyCode,
        parseBoolean(showLastCutOff));
  }

  static boolean parseBoolean(final String value) {
    if (StringUtils.isBlank(value)) {
      return false;
    }
    if ("true".equalsIgnoreCase(value)) {
      return true;
    }
    if ("false".equalsIgnoreCase(value)) {
      return false;
    }
    throw new GeneralPlatformDomainRuleException(
        "error.msg.base.teller.cash.inventory.show.last.cutoff.invalid",
        "showLastCutOff must be true or false.");
  }
}
