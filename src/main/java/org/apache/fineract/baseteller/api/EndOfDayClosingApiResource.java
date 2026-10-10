package org.apache.fineract.baseteller.api;

import static org.apache.fineract.baseteller.service.EndOfDayClosingCalculator.invalid;

import com.google.gson.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.baseteller.data.EndOfDayClosingData;
import org.apache.fineract.baseteller.data.EndOfDayClosingRequest;
import org.apache.fineract.baseteller.service.EndOfDayClosingPlatformService;
import org.springframework.stereotype.Component;

@Path("/v2/base-teller/end-of-day-closing")
@Component
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Base Teller End of Day Closing")
@RequiredArgsConstructor
public class EndOfDayClosingApiResource {
  private static final Gson GSON = new Gson();
  private final EndOfDayClosingPlatformService service;

  @GET
  @Path("/context")
  public EndOfDayClosingData.Context context() {
    return service.context();
  }

  @GET
  @Path("/checks")
  public List<EndOfDayClosingData.Check> checks(
      @QueryParam("cashierId") Long cashierId, @QueryParam("currencyCode") String currencyCode) {
    return service.checks(cashierId, currencyCode);
  }

  @POST
  @Path("/preview")
  public EndOfDayClosingData preview(String body) {
    return service.preview(parse(body, false).request());
  }

  @POST
  public EndOfDayClosingData complete(String body) {
    Parsed parsed = parse(body, true);
    try {
      return service.complete(parsed.request(), parsed.username, parsed.credential);
    } finally {
      parsed.credential = null;
    }
  }

  @GET
  @Path("/{id}")
  public EndOfDayClosingData retrieve(@PathParam("id") Long id) {
    return service.retrieve(id, false);
  }

  @GET
  @Path("/{id}/report")
  public EndOfDayClosingData report(@PathParam("id") Long id) {
    return service.retrieve(id, true);
  }

  // No record/Lombok/toString for this transient credential carrier. Parsing failures never echo
  // caller input or attach Gson exceptions that might contain a credential-bearing fragment.
  private static final class Parsed {
    private final EndOfDayClosingRequest request;
    private final String username;
    private String credential;

    private Parsed(EndOfDayClosingRequest request, String username, String credential) {
      this.request = request;
      this.username = username;
      this.credential = credential;
    }

    private EndOfDayClosingRequest request() {
      return request;
    }
  }

  private static Parsed parse(String body, boolean complete) {
    try {
      JsonObject object = JsonParser.parseString(body).getAsJsonObject();
      String username = null, credential = null;
      if (complete) {
        JsonObject auth = object.remove("authorization").getAsJsonObject();
        fields(auth, "username", "credential");
        username = auth.get("username").getAsString();
        credential = auth.get("credential").getAsString();
      }
      fields(object, "cashierId", "businessDate", "idempotencyKey", "currencies");
      integer(object.get("cashierId"));
      for (JsonElement currency : object.getAsJsonArray("currencies")) {
        JsonObject c = currency.getAsJsonObject();
        fields(c, "currencyCode", "denominations", "vouchers", "checks");
        for (JsonElement denomination : c.getAsJsonArray("denominations")) {
          JsonObject d = denomination.getAsJsonObject();
          fields(d, "denominationId", "quantity");
          integer(d.get("quantity"));
        }
        for (JsonElement check : c.getAsJsonArray("checks")) {
          JsonObject ch = check.getAsJsonObject();
          fields(ch, "source", "id");
          integer(ch.get("id"));
        }
      }
      return new Parsed(GSON.fromJson(object, EndOfDayClosingRequest.class), username, credential);
    } catch (RuntimeException e) {
      throw invalid("request.invalid", "Invalid closing declaration or authorization structure.");
    }
  }

  private static void fields(JsonObject object, String... allowed) {
    if (!java.util.Set.of(allowed).containsAll(object.keySet()))
      throw new IllegalArgumentException();
  }

  private static void integer(JsonElement value) {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException();
    value.getAsBigDecimal().longValueExact();
  }
}
