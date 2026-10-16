package org.apache.fineract.baseteller.cashtransfer;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;

public final class CashTransferValidation {
  private static final Gson GSON = new Gson();

  private CashTransferValidation() {}

  public static CashTransferRequest parse(String json, boolean create) {
    try {
      JsonObject body = GSON.fromJson(json, JsonObject.class);
      allowed(body, Set.of("destinationCashierId", "idempotencyKey", "currencies"));
      numeric(body.get("destinationCashierId"));
      if (body.has("idempotencyKey")
          && (!body.get("idempotencyKey").isJsonPrimitive()
              || !body.getAsJsonPrimitive("idempotencyKey").isString()))
        throw new IllegalArgumentException();
      for (var currency : body.getAsJsonArray("currencies")) {
        var row = currency.getAsJsonObject();
        allowed(row, Set.of("currencyCode", "denominations"));
        if (!row.getAsJsonPrimitive("currencyCode").isString())
          throw new IllegalArgumentException();
        for (var line : row.getAsJsonArray("denominations")) {
          allowed(line.getAsJsonObject(), Set.of("denominationId", "quantity"));
          if (!line.getAsJsonObject().getAsJsonPrimitive("denominationId").isString())
            throw new IllegalArgumentException();
          numeric(line.getAsJsonObject().get("quantity"));
        }
      }
      var request = GSON.fromJson(body, CashTransferRequest.class);
      validate(request, create);
      return request;
    } catch (RuntimeException e) {
      throw invalid(
          "request.malformed",
          "Invalid cash transfer request. Use destinationCashierId and currency denomination"
              + " quantities only.");
    }
  }

  private static void allowed(JsonObject value, Set<String> fields) {
    if (value == null || !fields.containsAll(value.keySet())) throw new IllegalArgumentException();
  }

  private static void numeric(JsonElement value) {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException();
    new BigDecimal(value.getAsString()).longValueExact();
  }

  public static void validate(CashTransferRequest r, boolean create) {
    if (r == null
        || r.destinationCashierId() == null
        || r.destinationCashierId() <= 0
        || r.currencies() == null
        || r.currencies().isEmpty()
        || r.currencies().size() > 100)
      throw invalid("request.invalid", "A destination and 1-100 currency selections are required.");
    if (create
        && (r.idempotencyKey() == null || !r.idempotencyKey().matches("[A-Za-z0-9._:-]{1,100}")))
      throw invalid(
          "idempotency.invalid",
          "An idempotencyKey of 1-100 letters, digits, dots, underscores, colons or hyphens is"
              + " required.");
    Set<String> codes = new HashSet<>();
    boolean positive = false;
    for (var c : r.currencies()) {
      if (c == null
          || c.currencyCode() == null
          || !c.currencyCode().matches("[A-Za-z]{3}")
          || !codes.add(c.currencyCode().toUpperCase(Locale.ROOT))
          || c.denominations() == null
          || c.denominations().isEmpty()
          || c.denominations().size() > 1000)
        throw invalid(
            "currency.invalid", "Currency selections must be unique and include denominations.");
      Set<String> ids = new HashSet<>();
      for (var d : c.denominations()) {
        if (d == null
            || d.denominationId() == null
            || d.denominationId().isBlank()
            || d.denominationId().length() > 100
            || !ids.add(d.denominationId()))
          throw invalid(
              "denomination.invalid",
              "Denomination identifiers must be unique within each currency.");
        long quantity = quantity(d.quantity());
        if (quantity > 0) positive = true;
      }
    }
    if (!positive) throw invalid("selection.empty", "Select at least one banknote or coin.");
  }

  public static long quantity(BigDecimal value) {
    try {
      long quantity = value.longValueExact();
      if (quantity < 0) throw new ArithmeticException();
      return quantity;
    } catch (ArithmeticException | NullPointerException e) {
      throw invalid(
          "quantity.invalid", "Quantity must be a nonnegative integer within the supported range.");
    }
  }

  /** Ordering, currency casing and decimal representation do not change request identity. */
  static String fingerprint(CashTransferRequest r, Long actor) {
    var currencies =
        r.currencies().stream()
            .map(
                c ->
                    new CashTransferRequest.Currency(
                        c.currencyCode().toUpperCase(Locale.ROOT),
                        c.denominations().stream()
                            .map(
                                d ->
                                    new CashTransferRequest.Quantity(
                                        d.denominationId(),
                                        BigDecimal.valueOf(quantity(d.quantity()))))
                            .sorted(
                                Comparator.comparing(CashTransferRequest.Quantity::denominationId))
                            .toList()))
            .sorted(Comparator.comparing(CashTransferRequest.Currency::currencyCode))
            .toList();
    return hash(
        actor
            + ":"
            + GSON.toJson(new CashTransferRequest(r.destinationCashierId(), null, currencies)));
  }

  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static GeneralPlatformDomainRuleException invalid(String code, String message) {
    return new GeneralPlatformDomainRuleException(
        "error.msg.base.teller.cash.transfer." + code, message);
  }
}
