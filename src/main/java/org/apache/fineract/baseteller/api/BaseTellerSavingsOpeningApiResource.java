package org.apache.fineract.baseteller.api;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.baseteller.data.BaseTellerCustomerData;
import org.apache.fineract.baseteller.data.BaseTellerCustomerPositionData;
import org.apache.fineract.baseteller.data.BaseTellerOpeningReceiptData;
import org.apache.fineract.baseteller.data.BaseTellerSavingsOpeningRequest;
import org.apache.fineract.baseteller.data.BaseTellerSavingsProductData;
import org.apache.fineract.baseteller.service.BaseTellerReadPlatformService;
import org.apache.fineract.baseteller.service.BaseTellerWritePlatformService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Component;

@Path("/v2/base-teller/savings-account-openings")
@Component
@Tag(name = "Base Teller Savings Account Openings")
@RequiredArgsConstructor
public class BaseTellerSavingsOpeningApiResource {

  private static final Gson GSON = new Gson();
  private static final String RESOURCE = "BASE_TELLER_SAVINGS_OPENING";

  private final PlatformSecurityContext context;
  private final BaseTellerReadPlatformService readPlatformService;
  private final BaseTellerWritePlatformService writePlatformService;

  @GET
  @Path("/customers")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Search customers for base teller savings account opening")
  public List<BaseTellerCustomerData> searchCustomers(
      @QueryParam("clientId") final Long clientId,
      @QueryParam("accountNo") final String accountNo,
      @QueryParam("q") final String query,
      @QueryParam("limit") final Integer limit) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE);
    return readPlatformService.searchCustomers(clientId, accountNo, query, limit);
  }

  @GET
  @Path("/customers/{clientId}/position")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Retrieve customer financial position for account opening")
  public BaseTellerCustomerPositionData retrieveCustomerPosition(
      @PathParam("clientId") final Long clientId) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE);
    return readPlatformService.retrieveCustomerPosition(clientId);
  }

  @GET
  @Path("/products")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Retrieve eligible savings products for account opening")
  public List<BaseTellerSavingsProductData> retrieveSavingsProducts(
      @QueryParam("currencyCode") final String currencyCode) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE);
    return readPlatformService.retrieveSavingsProducts(currencyCode);
  }

  @POST
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Open and initially fund a savings account for a base teller")
  public BaseTellerOpeningReceiptData openSavingsAccount(final String jsonRequestBody) {
    context.authenticatedUser().validateHasCreatePermission(RESOURCE);
    final BaseTellerSavingsOpeningRequest request =
        GSON.fromJson(jsonRequestBody, BaseTellerSavingsOpeningRequest.class);
    return writePlatformService.openSavingsAccount(request);
  }

  @GET
  @Path("/{receiptNumber}")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Retrieve receipt data for a base teller savings account opening")
  public BaseTellerOpeningReceiptData retrieveReceipt(
      @PathParam("receiptNumber") final String receiptNumber) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE);
    return readPlatformService.retrieveOpeningReceipt(receiptNumber);
  }
}
