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
import org.apache.fineract.baseteller.data.CreditPaymentContextData;
import org.apache.fineract.baseteller.data.CreditPaymentLoanData;
import org.apache.fineract.baseteller.data.CreditPaymentPreviewData;
import org.apache.fineract.baseteller.data.CreditPaymentReceiptData;
import org.apache.fineract.baseteller.data.CreditPaymentRequest;
import org.apache.fineract.baseteller.data.CreditPaymentTransitionRequest;
import org.apache.fineract.baseteller.service.CreditPaymentReadPlatformService;
import org.apache.fineract.baseteller.service.CreditPaymentWritePlatformService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Component;

@Path("/v2/base-teller/credit-payments")
@Component
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Base Teller Credit Payments")
@RequiredArgsConstructor
public class CreditPaymentsApiResource {

  private static final Gson GSON = new Gson();
  private static final String RESOURCE = "BASE_TELLER_CREDIT_PAYMENT";

  private final PlatformSecurityContext context;
  private final CreditPaymentReadPlatformService readService;
  private final CreditPaymentWritePlatformService writeService;

  @GET
  @Path("/context")
  @Operation(summary = "Retrieve authoritative teller, denomination, bank and payment context")
  public CreditPaymentContextData context() {
    context.authenticatedUser().validateHasReadPermission(RESOURCE);
    return readService.context();
  }

  @GET
  @Path("/customers")
  @Operation(summary = "Search account holders eligible for credit payments")
  public List<BaseTellerCustomerData> customers(
      @QueryParam("clientId") final Long clientId,
      @QueryParam("accountNo") final String accountNo,
      @QueryParam("q") final String query,
      @QueryParam("limit") final Integer limit) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE);
    return readService.searchCustomers(clientId, accountNo, query, limit);
  }

  @GET
  @Path("/customers/{clientId}/loans")
  @Operation(summary = "Retrieve a customer's loans and authoritative balances")
  public List<CreditPaymentLoanData> loans(@PathParam("clientId") final Long clientId) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE);
    return readService.loans(clientId);
  }

  @GET
  @Path("/loans/{loanId}")
  @Operation(summary = "Retrieve loan balances, repayment schedule and transaction history")
  public CreditPaymentLoanData loan(@PathParam("loanId") final Long loanId) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE);
    return readService.loan(loanId);
  }

  @POST
  @Path("/preview")
  @Operation(summary = "Preview a backend-authoritative cash or check credit payment")
  public CreditPaymentPreviewData preview(final String body) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE);
    return writeService.preview(GSON.fromJson(body, CreditPaymentRequest.class));
  }

  @POST
  @Operation(summary = "Create a cash repayment or accept a check")
  public CreditPaymentReceiptData create(final String body) {
    context.authenticatedUser().validateHasCreatePermission(RESOURCE);
    return writeService.create(GSON.fromJson(body, CreditPaymentRequest.class));
  }

  @GET
  @Path("/{receiptNumber}")
  @Operation(summary = "Retrieve or reprint an immutable credit-payment receipt")
  public CreditPaymentReceiptData receipt(@PathParam("receiptNumber") final String receiptNumber) {
    context.authenticatedUser().validateHasPermissionTo("REPRINT_BASE_TELLER_CREDIT_PAYMENT");
    return readService.receipt(receiptNumber);
  }

  @POST
  @Path("/checks/{checkId}/clear")
  @Operation(summary = "Authorize collection and post one native Fineract loan repayment")
  public CreditPaymentReceiptData clear(
      @PathParam("checkId") final Long checkId, final String body) {
    context.authenticatedUser().validateHasPermissionTo("AUTHORIZE_BASE_TELLER_CHECK_CLEARING");
    return writeService.clear(checkId, GSON.fromJson(body, CreditPaymentTransitionRequest.class));
  }

  @POST
  @Path("/checks/{checkId}/return")
  @Operation(summary = "Return an uncleared pending check without reducing the loan")
  public CreditPaymentReceiptData returnCheck(
      @PathParam("checkId") final Long checkId, final String body) {
    context.authenticatedUser().validateHasPermissionTo("RETURN_BASE_TELLER_CREDIT_PAYMENT_CHECK");
    return writeService.returnCheck(checkId, GSON.fromJson(body, CreditPaymentTransitionRequest.class));
  }
}
