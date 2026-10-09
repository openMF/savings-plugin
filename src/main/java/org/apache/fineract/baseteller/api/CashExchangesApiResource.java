package org.apache.fineract.baseteller.api;

import static org.apache.fineract.baseteller.validation.CashExchangeValidator.invalid;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.baseteller.data.CashExchangeContextData;
import org.apache.fineract.baseteller.data.CashExchangeData;
import org.apache.fineract.baseteller.data.CashExchangeInventoryData;
import org.apache.fineract.baseteller.data.CashExchangePreviewData;
import org.apache.fineract.baseteller.data.CashExchangeRequest;
import org.apache.fineract.baseteller.service.CashExchangePlatformService;

@Path("/v2/base-teller/cash-exchanges")
@org.springframework.stereotype.Component
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(
    name = "Base Teller Cash Exchanges",
    description =
        "Exact denomination exchange with zero accounting effect. READ for lookups, CREATE for"
            + " preview/create, REPRINT for receipts. Unauthorized drawers, unknown inventory and"
            + " invalid quantities or unequal totals are rejected.")
@RequiredArgsConstructor
public class CashExchangesApiResource {
  private static final Gson GSON = new Gson();
  private final CashExchangePlatformService service;

  @GET
  @Path("/context")
  @Operation(summary = "Retrieve authorized cashier drawers and configured currencies (READ)")
  public CashExchangeContextData context() {
    return service.context();
  }

  @GET
  @Path("/denominations")
  @Operation(summary = "Retrieve configured denominations and proven physical availability (READ)")
  public CashExchangeInventoryData denominations(
      @QueryParam("cashierId") Long cashierId, @QueryParam("currencyCode") String currencyCode) {
    return service.denominations(cashierId, currencyCode);
  }

  @POST
  @Path("/preview")
  @Operation(
      summary = "Validate exact equality and physical availability without persistence (CREATE)")
  public CashExchangePreviewData preview(String body) {
    return service.preview(parse(body));
  }

  @POST
  @Operation(summary = "Atomically persist one immutable, idempotent cash exchange (CREATE)")
  public CashExchangeData create(String body) {
    return service.create(parse(body));
  }

  @GET
  @Path("/{exchangeId}")
  @Operation(summary = "Retrieve the stored exchange (READ)")
  public CashExchangeData retrieve(@PathParam("exchangeId") Long id) {
    return service.retrieve(id);
  }

  @GET
  @Path("/{exchangeId}/receipt")
  @Operation(summary = "Retrieve an immutable receipt for printing or reprinting (REPRINT)")
  public CashExchangeData receipt(@PathParam("exchangeId") Long id) {
    return service.receipt(id);
  }

  static CashExchangeRequest parse(String body) {
    try {
      JsonObject json = JsonParser.parseString(body).getAsJsonObject();
      for (String key : json.keySet())
        if (!java.util.Set.of(
                "cashierId",
                "currencyCode",
                "receivedDenominations",
                "deliveredDenominations",
                "idempotencyKey")
            .contains(key)) throw invalid("field.unsupported", "Unsupported request field: " + key);
      integer(json.get("cashierId"));
      for (String side : java.util.List.of("receivedDenominations", "deliveredDenominations")) {
        for (JsonElement element : json.getAsJsonArray(side)) {
          JsonObject line = element.getAsJsonObject();
          for (String key : line.keySet())
            if (!java.util.Set.of("denominationId", "quantity").contains(key))
              throw invalid(
                  "field.unsupported",
                  "Only denominationId and quantity are accepted on denomination lines.");
          integer(line.get("quantity"));
          if (!line.get("denominationId").isJsonPrimitive()
              || !line.getAsJsonPrimitive("denominationId").isString())
            throw invalid(
                "denomination.invalid", "denominationId must be a catalog identifier string.");
        }
      }
      return GSON.fromJson(json, CashExchangeRequest.class);
    } catch (JsonParseException
        | IllegalStateException
        | NullPointerException
        | ArithmeticException
        | ClassCastException e) {
      throw invalid(
          "json.invalid",
          "A valid request with integer quantities within signed 64-bit range is required.");
    }
  }

  private static void integer(JsonElement value) {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw invalid("quantity.invalid", "Integer JSON numbers are required.");
    value.getAsBigDecimal().longValueExact();
  }
}
