package org.apache.fineract.baseteller.foreigncurrency;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;

/** Structural validation only: storage bounds do not prescribe a financial rate/rounding policy. */
public final class FxValidation {
  private static final Gson GSON = new Gson();
  public static final String RATES = "BASE_TELLER_FX_RATE";
  public static final String TELLER = "BASE_TELLER_FX";
  public static final List<String> BLOCKERS =
      List.of(
          "NATIONAL_CURRENCY_NOT_VERIFIED",
          "RATE_CONVENTION_NOT_APPROVED",
          "BRANCH_POINTS_FORMULA_NOT_APPROVED",
          "FX_ROUNDING_AND_LIMITS_NOT_APPROVED",
          "RATE_ACTIVATION_AND_REAUTH_POLICY_NOT_APPROVED",
          "CUSTOMER_IDENTIFICATION_POLICY_NOT_APPROVED",
          "FX_ACCOUNTING_AND_ATOMIC_EXECUTION_NOT_VERIFIED");

  private FxValidation() {}

  public static FxData.Capabilities capabilities() {
    return new FxData.Capabilities(false, false, false, false, BLOCKERS);
  }

  public static GeneralPlatformDomainRuleException invalid(String code, String message) {
    return new GeneralPlatformDomainRuleException("error.msg.base.teller.fx." + code, message);
  }

  public static void blocked(String operation) {
    throw invalid(
        "operation.unsupported",
        operation
            + " is disabled pending approved national currency, trading-rate, rounding, activation,"
            + " identification and atomic FX accounting policies.");
  }

  public static FxData.RateRequest rate(String json) {
    try {
      JsonObject o =
          object(
              json,
              Set.of(
                  "effectiveDate",
                  "baseCurrency",
                  "quoteCurrency",
                  "dailyBaseRate",
                  "branches",
                  "previewToken"));
      for (String field : List.of("effectiveDate", "baseCurrency", "quoteCurrency"))
        string(o.get(field));
      number(o.get("dailyBaseRate"));
      if (o.has("previewToken")) string(o.get("previewToken"));
      for (var item : o.getAsJsonArray("branches")) {
        var b = item.getAsJsonObject();
        allowed(b, Set.of("officeId", "expectedVersion", "buyPoints", "sellPoints"));
        number(b.get("officeId")).longValueExact();
        number(b.get("expectedVersion")).longValueExact();
        number(b.get("buyPoints"));
        number(b.get("sellPoints"));
      }
      var r = GSON.fromJson(o, FxData.RateRequest.class);
      validate(r);
      return r;
    } catch (GeneralPlatformDomainRuleException e) {
      throw e;
    } catch (RuntimeException e) {
      throw invalid("request.malformed", "Invalid rate draft JSON or field types.");
    }
  }

  public static FxData.TradeRequest trade(String json) {
    try {
      JsonObject o =
          object(
              json,
              Set.of(
                  "clientId",
                  "identifierId",
                  "reference",
                  "quoteCurrency",
                  "usdAmount",
                  "rateDraftId",
                  "rateVersion",
                  "usdDenominations",
                  "quoteDenominations"));
      for (String field : List.of("clientId", "rateDraftId", "rateVersion"))
        number(o.get(field)).longValueExact();
      if (o.has("identifierId")) number(o.get("identifierId")).longValueExact();
      string(o.get("quoteCurrency"));
      string(o.get("reference"));
      number(o.get("usdAmount"));
      for (String side : List.of("usdDenominations", "quoteDenominations"))
        for (var item : o.getAsJsonArray(side)) {
          var line = item.getAsJsonObject();
          allowed(line, Set.of("denominationId", "quantity"));
          string(line.get("denominationId"));
          number(line.get("quantity")).longValueExact();
        }
      var r = GSON.fromJson(o, FxData.TradeRequest.class);
      validate(r);
      return r;
    } catch (GeneralPlatformDomainRuleException e) {
      throw e;
    } catch (RuntimeException e) {
      throw invalid("request.malformed", "Invalid FX preview JSON or field types.");
    }
  }

  public static void validate(FxData.RateRequest r) {
    if (r == null) throw invalid("request.required", "A rate draft request is required.");
    date(r.effectiveDate());
    pair(r.baseCurrency(), r.quoteCurrency());
    decimal(r.dailyBaseRate(), true); // Exact DECIMAL(32,12) storage; no rounding.
    if (r.branches() == null || r.branches().isEmpty() || r.branches().size() > 100)
      throw invalid("branches.invalid", "Select 1-100 branches.");
    var ids = new HashSet<Long>();
    for (var b : r.branches()) {
      if (b == null
          || b.officeId() == null
          || b.officeId() <= 0
          || !ids.add(b.officeId())
          || b.expectedVersion() == null
          || b.expectedVersion() < 0
          || b.expectedVersion() == Long.MAX_VALUE)
        throw invalid(
            "branch.invalid", "Branches must be unique with valid IDs and expected versions.");
      decimal(b.buyPoints(), false);
      decimal(b.sellPoints(), false);
    }
  }

  public static void validate(FxData.TradeRequest r) {
    if (r == null
        || r.clientId() == null
        || r.clientId() <= 0
        || r.rateDraftId() == null
        || r.rateDraftId() <= 0
        || r.rateVersion() == null
        || r.rateVersion() <= 0
        || (r.identifierId() != null && r.identifierId() <= 0))
      throw invalid("request.invalid", "A customer and candidate rate ID/version are required.");
    pair("USD", r.quoteCurrency());
    decimal(r.usdAmount(), true);
    if (r.reference() == null
        || r.reference().isBlank()
        || r.reference().length() > 100
        || r.reference().chars().anyMatch(Character::isISOControl))
      throw invalid("reference.invalid", "A reference of 1-100 printable characters is required.");
    quantities(r.usdDenominations());
    quantities(r.quoteDenominations());
  }

  public static LocalDate date(String value) {
    try {
      if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
        throw new IllegalArgumentException();
      var date = LocalDate.parse(value);
      if (date.getYear() < 1) throw new IllegalArgumentException();
      return date;
    } catch (RuntimeException e) {
      throw invalid("date.invalid", "Use a valid ISO business date.");
    }
  }

  public static void pair(String base, String quote) {
    if (!"USD".equals(base) || quote == null || !quote.matches("[A-Z]{3}") || "USD".equals(quote))
      throw invalid(
          "currency.pair", "The draft pair must be USD and a distinct enabled quote currency.");
  }

  public static void decimal(BigDecimal v, boolean positive) {
    if (v == null
        || (positive && v.signum() <= 0)
        || v.scale() > 12
        || v.scale() < -20
        || v.precision() - v.scale() > 20)
      throw invalid(
          "decimal.invalid",
          "Values must fit exact DECIMAL(32,12); amounts and base rates must be positive.");
  }

  public static long quantity(BigDecimal value) {
    try {
      long q = value.longValueExact();
      if (q < 0) throw new ArithmeticException();
      return q;
    } catch (ArithmeticException | NullPointerException e) {
      throw invalid("quantity.invalid", "Quantity must be a nonnegative signed 64-bit integer.");
    }
  }

  private static void quantities(List<FxData.Quantity> lines) {
    if (lines == null || lines.isEmpty() || lines.size() > 1000)
      throw invalid("denominations.required", "Both currencies require 1-1000 denomination rows.");
    var ids = new HashSet<String>();
    for (var d : lines) {
      if (d == null
          || d.denominationId() == null
          || d.denominationId().isBlank()
          || d.denominationId().length() > 100
          || !ids.add(d.denominationId()))
        throw invalid(
            "denomination.invalid", "Denomination IDs must be present and unique per currency.");
      quantity(d.quantity());
    }
  }

  private static JsonObject object(String json, Set<String> fields) {
    if (json == null || json.length() > 250_000) throw new IllegalArgumentException();
    var o = GSON.fromJson(json, JsonObject.class);
    allowed(o, fields);
    return o;
  }

  private static void allowed(JsonObject o, Set<String> fields) {
    if (o == null || !fields.containsAll(o.keySet())) throw new IllegalArgumentException();
  }

  private static void string(JsonElement v) {
    if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString())
      throw new IllegalArgumentException();
  }

  private static BigDecimal number(JsonElement v) {
    if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException();
    return v.getAsBigDecimal();
  }
}
