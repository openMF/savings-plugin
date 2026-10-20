package org.apache.fineract.baseteller.foreigncurrency;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.Page;
import org.springframework.stereotype.Component;

@Component
@Path("/v2/base-teller/foreign-currency")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Tag(
    name = "Base Teller Foreign Currency",
    description = "Read-only, incomplete previews. Purchase and sale execution is disabled.")
@RequiredArgsConstructor
public class FxTellerApiResource {
  private final FxTellerService service;

  @GET
  @Path("/context")
  public FxData.TellerContext context() {
    return service.context();
  }

  @GET
  @Path("/customers")
  public Page<FxData.Customer> customers(
      @QueryParam("clientNumber") String number,
      @QueryParam("name") String name,
      @QueryParam("offset") Integer offset,
      @QueryParam("limit") Integer limit) {
    return service.customers(number, name, offset, limit);
  }

  @GET
  @Path("/denominations")
  public List<FxData.Denomination> denominations(@QueryParam("currencyCode") String code) {
    return service.denominations(code);
  }

  @POST
  @Path("/sales/preview")
  public FxData.TradePreview salePreview(String body) {
    return service.preview(FxData.Operation.SALE, FxValidation.trade(body));
  }

  @POST
  @Path("/purchases/preview")
  public FxData.TradePreview purchasePreview(String body) {
    return service.preview(FxData.Operation.PURCHASE, FxValidation.trade(body));
  }

  @POST
  @Path("/sales")
  @Operation(summary = "Always rejects USD sale execution without any financial effects")
  public void sale(String body) {
    service.confirm(FxData.Operation.SALE);
  }

  @POST
  @Path("/purchases")
  @Operation(summary = "Always rejects USD purchase execution without any financial effects")
  public void purchase(String body) {
    service.confirm(FxData.Operation.PURCHASE);
  }
}
