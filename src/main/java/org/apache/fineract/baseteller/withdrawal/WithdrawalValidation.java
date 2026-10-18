package org.apache.fineract.baseteller.withdrawal;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import org.apache.fineract.baseteller.cashtransfer.CashTransferValidation;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;

public final class WithdrawalValidation {
  private static final Gson GSON = new Gson();

  private WithdrawalValidation() {}

  public static WithdrawalRequest parse(String json, boolean create) {
    WithdrawalRequest request;
    try {
      JsonObject body = GSON.fromJson(json, JsonObject.class);
      allowed(
          body,
          Set.of(
              "clientId",
              "accountId",
              "method",
              "amount",
              "paymentTypeId",
              "identificationTypeId",
              "identificationReference",
              "denominations",
              "idempotencyKey"));
      for (String id : Set.of("clientId", "accountId", "paymentTypeId", "identificationTypeId"))
        number(body.get(id)).longValueExact();
      number(body.get("amount"));
      for (String name : Set.of("method", "identificationReference")) string(body.get(name));
      if (body.has("idempotencyKey")) string(body.get("idempotencyKey"));
      for (var line : body.getAsJsonArray("denominations")) {
        var d = line.getAsJsonObject();
        allowed(d, Set.of("denominationId", "quantity"));
        string(d.get("denominationId"));
        number(d.get("quantity")).longValueExact();
      }
      request = GSON.fromJson(body, WithdrawalRequest.class);
    } catch (RuntimeException e) {
      throw invalid("request.malformed", "Invalid withdrawal fields or JSON types.");
    }
    validate(request, create);
    return request;
  }

  private static BigDecimal number(JsonElement v) {
    if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException();
    return v.getAsBigDecimal();
  }

  private static void string(JsonElement v) {
    if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString())
      throw new IllegalArgumentException();
  }

  private static void allowed(JsonObject v, Set<String> fields) {
    if (v == null || !fields.containsAll(v.keySet())) throw new IllegalArgumentException();
  }

  public static void validate(WithdrawalRequest r, boolean create) {
    if (r == null
        || r.clientId() == null
        || r.clientId() <= 0
        || r.accountId() == null
        || r.accountId() <= 0
        || r.paymentTypeId() == null
        || r.paymentTypeId() <= 0
        || r.method() == null
        || r.amount() == null
        || r.amount().signum() <= 0
        || r.amount().compareTo(new BigDecimal("10000000000000")) >= 0)
      throw invalid(
          "request.invalid",
          "A customer, account, method, cash payment type and positive amount are required.");
    if (r.method() != WithdrawalRequest.Method.CASH)
      throw invalid("check.unsupported", WithdrawalPlatformService.CHECK_BLOCKER);
    if (r.identificationTypeId() == null
        || r.identificationTypeId() <= 0
        || r.identificationReference() == null
        || r.identificationReference().isBlank()
        || r.identificationReference().length() > 1000
        || r.identificationReference().chars().anyMatch(Character::isISOControl))
      throw invalid(
          "identification.invalid",
          "A supported identification type and reference of 1-1000 characters are required.");
    if (create
        && (r.idempotencyKey() == null || !r.idempotencyKey().matches("[A-Za-z0-9._:-]{1,100}")))
      throw invalid(
          "idempotency.invalid",
          "An idempotencyKey of 1-100 letters, digits, dots, underscores, colons or hyphens is"
              + " required.");
    if (r.denominations() == null || r.denominations().isEmpty() || r.denominations().size() > 1000)
      throw invalid("denomination.required", "Select 1-1000 denomination rows.");
    var ids = new HashSet<String>();
    for (var d : r.denominations()) {
      if (d == null
          || d.denominationId() == null
          || d.denominationId().isBlank()
          || d.denominationId().length() > 100
          || !ids.add(d.denominationId()))
        throw invalid(
            "denomination.invalid", "Denomination identifiers must be present and unique.");
      quantity(d.quantity());
    }
  }

  public static long quantity(BigDecimal value) {
    try {
      long q = value.longValueExact();
      if (q < 0) throw new ArithmeticException();
      return q;
    } catch (ArithmeticException | NullPointerException e) {
      throw invalid(
          "quantity.invalid", "Quantity must be a nonnegative integer within the supported range.");
    }
  }

  static String fingerprint(WithdrawalRequest r, Long actor) {
    var rows =
        r.denominations().stream()
            .filter(d -> quantity(d.quantity()) > 0)
            .map(
                d ->
                    new WithdrawalRequest.Quantity(
                        d.denominationId(), BigDecimal.valueOf(quantity(d.quantity()))))
            .sorted(Comparator.comparing(WithdrawalRequest.Quantity::denominationId))
            .toList();
    return CashTransferValidation.hash(
        actor
            + ":"
            + GSON.toJson(
                new WithdrawalRequest(
                    r.clientId(),
                    r.accountId(),
                    r.method(),
                    r.amount().stripTrailingZeros(),
                    r.paymentTypeId(),
                    r.identificationTypeId(),
                    r.identificationReference(),
                    rows,
                    null)));
  }

  static String mask(String reference) {
    return "****" + (reference.length() > 4 ? reference.substring(reference.length() - 4) : "");
  }

  public static GeneralPlatformDomainRuleException invalid(String code, String message) {
    return new GeneralPlatformDomainRuleException(
        "error.msg.base.teller.withdrawal." + code, message);
  }
}
