package org.apache.fineract.baseteller.cashtransfer;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Path("/v2/base-teller/cash-transfers")
@Component
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Base Teller Cash Transfers")
@RequiredArgsConstructor
public class CashTransfersApiResource {
  private final CashTransferPlatformService service;

  @GET
  @Path("/context")
  @Operation(summary = "Resolve source cashier, eligible destinations and actual denominations")
  public CashTransferData.Context context() {
    return service.context();
  }

  @POST
  @Path("/preview")
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(summary = "Validate and preview a balanced cash custody transfer without mutation")
  public CashTransferData preview(String json) {
    return service.preview(CashTransferValidation.parse(json, false));
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(summary = "Atomically transfer denomination custody using native cashier services")
  public CashTransferData create(String json) {
    return service.create(CashTransferValidation.parse(json, true));
  }

  @GET
  @Path("/{transferId}")
  public CashTransferData retrieve(@PathParam("transferId") Long id) {
    return service.retrieve(id);
  }

  @GET
  @Path("/{transferId}/receipt")
  @Operation(summary = "Reprint persisted receipt data")
  public CashTransferData receipt(@PathParam("transferId") Long id) {
    return service.receipt(id);
  }

  @GET
  @Path("/{transferId}/receipt")
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Print a historical receipt (Accept: text/html)")
  public String printableReceipt(@PathParam("transferId") Long id) {
    return CashTransferReceipt.html(service.receipt(id));
  }
}
