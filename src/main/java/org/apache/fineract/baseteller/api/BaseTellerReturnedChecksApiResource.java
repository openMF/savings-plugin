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
import lombok.RequiredArgsConstructor;
import org.apache.fineract.baseteller.data.BaseTellerReturnedCheckDetailData;
import org.apache.fineract.baseteller.data.BaseTellerReturnedCheckPaymentRequest;
import org.apache.fineract.baseteller.data.BaseTellerReturnedCheckReceiptData;
import org.apache.fineract.baseteller.data.BaseTellerReturnedCheckSearchData;
import org.apache.fineract.baseteller.service.BaseTellerReadPlatformService;
import org.apache.fineract.baseteller.service.BaseTellerWritePlatformService;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Component;

@Path("/v2/base-teller/returned-checks")
@Component
@Tag(name = "Base Teller Returned Checks")
@RequiredArgsConstructor
public class BaseTellerReturnedChecksApiResource {

  private static final Gson GSON = new Gson();
  private static final String RESOURCE = "BASE_TELLER_RETURNED_CHECK_PAYMENT";

  private final PlatformSecurityContext context;
  private final BaseTellerReadPlatformService readPlatformService;
  private final BaseTellerWritePlatformService writePlatformService;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Search returned checks eligible for base teller payment")
  public Page<BaseTellerReturnedCheckSearchData> search(
      @QueryParam("date") final String returnedOnDate,
      @QueryParam("customerName") final String customerName,
      @QueryParam("tellerId") final Long tellerId,
      @QueryParam("currencyCode") final String currencyCode,
      @QueryParam("checkNumber") final String checkNumber,
      @QueryParam("clientId") final Long clientId,
      @QueryParam("officeId") final Long officeId,
      @QueryParam("status") final String status,
      @QueryParam("offset") final Integer offset,
      @QueryParam("limit") final Integer limit) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE);
    return readPlatformService.searchReturnedChecks(
        returnedOnDate,
        customerName,
        tellerId,
        currencyCode,
        checkNumber,
        clientId,
        officeId,
        status,
        offset,
        limit);
  }

  @GET
  @Path("/receipts/{receiptNumber}")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Retrieve a returned-check payment receipt")
  public BaseTellerReturnedCheckReceiptData retrieveReceipt(
      @PathParam("receiptNumber") final String receiptNumber) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE);
    return readPlatformService.retrieveReturnedCheckReceipt(receiptNumber);
  }

  @POST
  @Path("/{returnedCheckId}/settle")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Settle a returned check with customer cash")
  public BaseTellerReturnedCheckReceiptData settle(
      @PathParam("returnedCheckId") final Long returnedCheckId, final String jsonRequestBody) {
    context.authenticatedUser().validateHasCreatePermission(RESOURCE);
    final BaseTellerReturnedCheckPaymentRequest request =
        GSON.fromJson(jsonRequestBody, BaseTellerReturnedCheckPaymentRequest.class);
    return writePlatformService.settleReturnedCheck(returnedCheckId, request);
  }

  @GET
  @Path("/{returnedCheckId}")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Retrieve a returned check selected for base teller payment")
  public BaseTellerReturnedCheckDetailData retrieve(
      @PathParam("returnedCheckId") final Long returnedCheckId) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE);
    return readPlatformService.retrieveReturnedCheck(returnedCheckId);
  }
}
