package org.apache.fineract.baseteller.api;

import static org.apache.fineract.baseteller.service.TransactionReversalPlatformService.invalid;

import com.google.gson.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.baseteller.data.TransactionReversalData.*;
import org.apache.fineract.baseteller.data.TransactionReversalRequest;
import org.apache.fineract.baseteller.service.TransactionReversalPlatformService;
import org.springframework.stereotype.Component;

@Path("/v2/base-teller/transaction-reversals")
@Component
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Base Teller Transaction Reversal")
@RequiredArgsConstructor
public class TransactionReversalsApiResource {
  private static final Gson GSON = new Gson();
  private final TransactionReversalPlatformService service;

  @GET
  @Path("/context")
  public Context context() {
    return service.context();
  }

  @GET
  @Path("/transactions")
  public Search search(
      @QueryParam("transactionDate") String date,
      @QueryParam("offset") Integer offset,
      @QueryParam("limit") Integer limit,
      @QueryParam("sort") String sort,
      @QueryParam("order") String order) {
    LocalDate parsed;
    try {
      parsed = date == null ? null : LocalDate.parse(date);
    } catch (RuntimeException e) {
      throw invalid("date.invalid", "transactionDate must use ISO yyyy-MM-dd format.");
    }
    return service.search(parsed, offset, limit, sort, order);
  }

  @GET
  @Path("/{historyId}")
  public Detail detail(@PathParam("historyId") String historyId) {
    return service.detail(historyId);
  }

  @GET
  @Path("/reversals/{id}")
  public Result retrieve(@PathParam("id") Long id) {
    return service.retrieve(id);
  }

  @POST
  @Path("/preview")
  public Preview preview(String body) {
    Parsed parsed = parse(body);
    try {
      return service.preview(parsed.request, parsed.number);
    } finally {
      parsed.number = null;
    }
  }

  @POST
  public Result reverse(String body) {
    Parsed parsed = parse(body);
    try {
      return service.reverse(parsed.request, parsed.number);
    } finally {
      parsed.number = null;
    }
  }

  @POST
  @Path("/authorizations")
  public TransactionReversalPlatformService.IssuedAuthorization issue(String body) {
    JsonObject json = object(body);
    allowed(json, Set.of("historyId", "cashierUserId"));
    integerId(json, "cashierUserId");
    try {
      return service.issue(
          json.get("historyId").getAsString(), json.get("cashierUserId").getAsLong());
    } catch (NullPointerException | IllegalStateException | NumberFormatException e) {
      throw invalid("request.invalid", "historyId and cashierUserId are required.");
    }
  }

  private static Parsed parse(String body) {
    JsonObject json = object(body);
    allowed(
        json,
        Set.of(
            "historyId",
            "idempotencyKey",
            "authorizationNumber",
            "cashDenominations",
            "returnedCashDenominations",
            "checks"));
    for (String field : Set.of("cashDenominations", "returnedCashDenominations"))
      if (json.has(field) && !json.get(field).isJsonNull()) {
        if (!json.get(field).isJsonArray())
          throw invalid("request.invalid", "Denominations must be an array.");
        for (var line : json.getAsJsonArray(field)) {
          if (!line.isJsonObject()) throw invalid("request.invalid", "Invalid denomination entry.");
          allowed(line.getAsJsonObject(), Set.of("denominationId", "quantity"));
          JsonElement q = line.getAsJsonObject().get("quantity");
          try {
            if (q == null || !q.isJsonPrimitive() || !q.getAsJsonPrimitive().isNumber())
              throw new ArithmeticException();
            q.getAsBigDecimal().longValueExact();
          } catch (RuntimeException e) {
            throw invalid("cash.quantity", "Denomination quantity must be an integer.");
          }
        }
      }
    if (json.has("checks") && !json.get("checks").isJsonNull()) {
      if (!json.get("checks").isJsonArray())
        throw invalid("request.invalid", "checks must be an array.");
      for (var check : json.getAsJsonArray("checks")) {
        if (!check.isJsonObject()) throw invalid("request.invalid", "Invalid check entry.");
        allowed(check.getAsJsonObject(), Set.of("id", "bankId", "checkNumber", "amount"));
        integerId(check.getAsJsonObject(), "id");
        integerId(check.getAsJsonObject(), "bankId");
      }
    }
    String number;
    try {
      number =
          json.has("authorizationNumber") ? json.remove("authorizationNumber").getAsString() : null;
    } catch (RuntimeException e) {
      throw invalid("authorization.required", "Authorization number is required.");
    }
    try {
      return new Parsed(GSON.fromJson(json, TransactionReversalRequest.class), number);
    } catch (RuntimeException e) {
      throw invalid("request.invalid", "Invalid reversal request.");
    }
  }

  private static JsonObject object(String body) {
    try {
      return JsonParser.parseString(body).getAsJsonObject();
    } catch (RuntimeException e) {
      throw invalid("request.invalid", "A JSON object is required.");
    }
  }

  private static void integerId(JsonObject json, String field) {
    JsonElement value = json.get(field);
    if (value == null || value.isJsonNull()) return;
    try {
      if (!value.isJsonPrimitive()
          || !value.getAsJsonPrimitive().isNumber()
          || value.getAsBigDecimal().longValueExact() <= 0) throw new ArithmeticException();
    } catch (RuntimeException exception) {
      throw invalid("request.id", "Identifiers must be positive integers.");
    }
  }

  private static void allowed(JsonObject json, Set<String> fields) {
    if (!fields.containsAll(json.keySet()))
      throw invalid("request.fields", "Unsupported request fields.");
  }

  private static final class Parsed {
    private final TransactionReversalRequest request;
    private String number;

    private Parsed(TransactionReversalRequest request, String number) {
      this.request = request;
      this.number = number;
    }
  }
}
