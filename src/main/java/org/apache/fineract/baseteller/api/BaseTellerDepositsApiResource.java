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
import org.apache.fineract.baseteller.data.BaseTellerDepositReceiptData;
import org.apache.fineract.baseteller.data.BaseTellerDepositRequest;
import org.apache.fineract.baseteller.service.BaseTellerReadPlatformService;
import org.apache.fineract.baseteller.service.BaseTellerWritePlatformService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Component;

@Path("/v2/base-teller/deposits")
@Component
@Tag(name = "Base Teller Deposits")
@RequiredArgsConstructor
public class BaseTellerDepositsApiResource {

  private static final Gson GSON = new Gson();
  private static final String RESOURCE = "BASE_TELLER_DEPOSIT";

  private final PlatformSecurityContext context;
  private final BaseTellerReadPlatformService readPlatformService;
  private final BaseTellerWritePlatformService writePlatformService;

  @GET
  @Path("/customers")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Search customers for base teller deposits")
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
  @Operation(summary = "Retrieve customer savings accounts for base teller deposits")
  public BaseTellerCustomerPositionData retrieveCustomerPosition(
      @PathParam("clientId") final Long clientId) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE);
    return readPlatformService.retrieveCustomerPosition(clientId);
  }

  @POST
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Deposit cash or a cleared check into an existing savings account")
  public BaseTellerDepositReceiptData deposit(final String jsonRequestBody) {
    context.authenticatedUser().validateHasCreatePermission(RESOURCE);
    final BaseTellerDepositRequest request =
        GSON.fromJson(jsonRequestBody, BaseTellerDepositRequest.class);
    return writePlatformService.deposit(request);
  }

  @GET
  @Path("/{receiptNumber}")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Retrieve receipt data for a base teller deposit")
  public BaseTellerDepositReceiptData retrieveReceipt(
      @PathParam("receiptNumber") final String receiptNumber) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE);
    return readPlatformService.retrieveDepositReceipt(receiptNumber);
  }
}
