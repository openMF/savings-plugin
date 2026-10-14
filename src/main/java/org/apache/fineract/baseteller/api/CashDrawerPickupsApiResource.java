package org.apache.fineract.baseteller.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import java.math.BigDecimal;
import java.util.Set;
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
import org.apache.fineract.baseteller.data.CashDrawerPickupContextData;
import org.apache.fineract.baseteller.data.CashDrawerPickupData;
import org.apache.fineract.baseteller.data.CashDrawerPickupRequest;
import org.apache.fineract.baseteller.service.CashDrawerPickupPlatformService;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.springframework.stereotype.Component;

@Path("/v2/base-teller/cash-drawer-pickups")
@Component
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Base Teller Cash Drawer Pickups")
@RequiredArgsConstructor
public class CashDrawerPickupsApiResource {
  private static final Gson GSON = new Gson();
  private final CashDrawerPickupPlatformService service;

  @GET
  @Path("/context")
  @Operation(summary = "Get pickup context")
  public CashDrawerPickupContextData context() { return service.context(); }

  @GET
  @Path("/checks")
  @Operation(summary = "Get eligible checks in source custody")
  public List<CashDrawerPickupData.Check> checks(@QueryParam("cashierId") Long cashierId) {
    return service.checks(cashierId);
  }

  @POST
  @Path("/preview")
  @Operation(summary = "Preview custody transfer without mutation")
  public CashDrawerPickupData preview(String json) {
    return service.preview(parse(json, false).request);
  }

  @POST
  @Operation(summary = "Complete an authorized pickup")
  public CashDrawerPickupData create(String json) {
    Parsed parsed = parse(json, true);
    try {
      return service.create(parsed.request, parsed.username, parsed.credential);
    } finally {
      parsed.credential = null;
    }
  }

  private static Parsed parse(String json, boolean create) {
    try {
      return parseValidated(json, create);
    } catch (RuntimeException malformed) {
      // Gson exceptions can include fragments of malformed input. Never attach a credential
      // bearing request or exception as a cause to the HTTP error.
      throw new GeneralPlatformDomainRuleException(
          "error.msg.base.teller.pickup.request.malformed", "Invalid pickup request.");
    }
  }

  private static Parsed parseValidated(String json, boolean create) {
    JsonObject body = GSON.fromJson(json, JsonObject.class);
    if (body == null) throw new IllegalArgumentException("Pickup request is required.");
    allowed(body, create
        ? Set.of("cashierId", "destinationCashierId", "idempotencyKey", "currencies", "authorizationUsername", "authorizationCredential")
        : Set.of("cashierId", "destinationCashierId", "idempotencyKey", "currencies"));
    if (!body.has("currencies") || !body.get("currencies").isJsonArray()) throw new IllegalArgumentException("currencies must be an array.");
    for (JsonElement currency : body.getAsJsonArray("currencies")) {
      if (!currency.isJsonObject()) throw new IllegalArgumentException("Invalid currency row.");
      JsonObject row = currency.getAsJsonObject();
      allowed(row, Set.of("currencyCode", "cashDenominations", "checks"));
      if (!row.has("cashDenominations") || !row.get("cashDenominations").isJsonArray()
          || !row.has("checks") || !row.get("checks").isJsonArray())
        throw new IllegalArgumentException("Cash and check selections must be arrays.");
      for (JsonElement line : row.getAsJsonArray("cashDenominations")) {
        if (!line.isJsonObject()) throw new IllegalArgumentException("Invalid denomination row.");
        allowed(line.getAsJsonObject(), Set.of("denominationId", "quantity"));
        JsonElement quantity = line.getAsJsonObject().get("quantity");
        if (quantity == null || !quantity.isJsonPrimitive() || !quantity.getAsJsonPrimitive().isNumber())
          throw new IllegalArgumentException("Quantity must be a numeric integer.");
        try { new BigDecimal(quantity.getAsString()).longValueExact(); }
        catch (NumberFormatException | ArithmeticException e) { throw new IllegalArgumentException("Quantity must be an integer in range."); }
      }
      for (JsonElement check : row.getAsJsonArray("checks")) {
        if (!check.isJsonObject()) throw new IllegalArgumentException("Invalid check selection.");
        allowed(check.getAsJsonObject(), Set.of("source", "id"));
      }
    }
    String username = string(body, "authorizationUsername");
    String credential = string(body, "authorizationCredential");
    body.remove("authorizationUsername");
    body.remove("authorizationCredential");
    return new Parsed(GSON.fromJson(body, CashDrawerPickupRequest.class), username, credential);
  }

  private static String string(JsonObject body, String key) {
    return body.has(key) && !body.get(key).isJsonNull() ? body.get(key).getAsString() : null;
  }

  private static void allowed(JsonObject object, Set<String> fields) {
    for (String key : object.keySet())
      if (!fields.contains(key)) throw new IllegalArgumentException("Unexpected pickup field.");
  }

  // Keep the credential out of generated record and Lombok toString methods.
  private static final class Parsed {
    private final CashDrawerPickupRequest request;
    private final String username;
    private String credential;

    private Parsed(CashDrawerPickupRequest request, String username, String credential) {
      this.request = request;
      this.username = username;
      this.credential = credential;
    }
  }

  @GET
  @Path("/{pickupId}")
  @Operation(summary = "Get completed pickup")
  public CashDrawerPickupData retrieve(@PathParam("pickupId") Long id) { return service.retrieve(id); }

  @GET
  @Path("/{pickupId}/receipt")
  @Operation(summary = "Reprint historical pickup receipt")
  public CashDrawerPickupData receipt(@PathParam("pickupId") Long id) { return service.receipt(id); }
}
