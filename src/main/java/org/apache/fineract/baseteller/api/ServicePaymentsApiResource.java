package org.apache.fineract.baseteller.api;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.baseteller.data.BaseTellerCustomerData;
import org.apache.fineract.baseteller.data.ServicePaymentQuoteData;
import org.apache.fineract.baseteller.data.ServicePaymentQuoteRequest;
import org.apache.fineract.baseteller.data.ServicePaymentReceiptData;
import org.apache.fineract.baseteller.data.ServicePaymentRequest;
import org.apache.fineract.baseteller.data.ServicePaymentServiceData;
import org.apache.fineract.baseteller.service.ServicePaymentReadPlatformService;
import org.apache.fineract.baseteller.service.ServicePaymentWritePlatformService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Component;

@Path("/v2/base-teller/service-payments")
@Component
@Tag(name = "Base Teller Service Payments")
@RequiredArgsConstructor
public class ServicePaymentsApiResource {

  private static final String RESOURCE = "BASE_TELLER_SERVICE_PAYMENT";
  private static final Gson GSON = new Gson();

  private final PlatformSecurityContext context;
  private final ServicePaymentReadPlatformService readService;
  private final ServicePaymentWritePlatformService writeService;

  @GET
  @Path("/services")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "List active bill and service payment services")
  public List<ServicePaymentServiceData> services() {
    context.authenticatedUser().validateHasReadPermission(RESOURCE);
    return readService.services();
  }

  @GET
  @Path("/clients/{clientId}")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Resolve an authoritative client payer")
  public BaseTellerCustomerData client(@PathParam("clientId") final Long clientId) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE);
    return readService.client(clientId);
  }

  @POST
  @Path("/quote")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Calculate an authoritative service payment quote without posting")
  public ServicePaymentQuoteData quote(final String jsonRequestBody) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE);
    return readService.quote(GSON.fromJson(jsonRequestBody, ServicePaymentQuoteRequest.class));
  }

  @POST
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Receive and post a cash bill or service payment")
  public ServicePaymentReceiptData create(final String jsonRequestBody) {
    context.authenticatedUser().validateHasCreatePermission(RESOURCE);
    return writeService.create(GSON.fromJson(jsonRequestBody, ServicePaymentRequest.class));
  }

  @GET
  @Path("/{transactionId}")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Retrieve or reprint an immutable service payment receipt")
  public ServicePaymentReceiptData receipt(@PathParam("transactionId") final Long transactionId) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE);
    return readService.receipt(transactionId);
  }

  @GET
  @Path("/{transactionId}/receipt")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Retrieve immutable receipt data without financial side effects")
  public ServicePaymentReceiptData reprint(@PathParam("transactionId") final Long transactionId) {
    return receipt(transactionId);
  }
}
