package org.apache.fineract.baseteller.service;

import com.google.gson.Gson;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.commands.service.SynchronousCommandProcessingService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.domain.FineractRequestContextHolder;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.organisation.teller.service.TellerWritePlatformService;

/** Native cashier posting shared by cash custody workflows. */
public final class CashCustodyPosting {
  private static final Gson GSON = new Gson();

  private CashCustodyPosting() {}

  /** Internal legs of a separately authorized logical custody operation; never queue a leg. */
  public static Long nativeMovement(
      TellerWritePlatformService tellers,
      FromJsonHelper jsonHelper,
      Long cashierId,
      boolean outgoing,
      BigDecimal amount,
      String currency,
      LocalDate date,
      String note) {
    String json = GSON.toJson(body(amount, currency, date, note));
    JsonCommand command =
        JsonCommand.fromJsonElement(cashierId, jsonHelper.parse(json), jsonHelper);
    CommandProcessingResult result =
        outgoing
            ? tellers.settleCashFromCashier(cashierId, command)
            : tellers.allocateCashToCashier(cashierId, command);
    return transactionId(result);
  }

  private static Map<String, Object> body(
      BigDecimal amount, String currency, LocalDate date, String note) {
    return Map.of(
        "currencyCode",
        currency,
        "txnAmount",
        amount,
        "txnNote",
        note,
        "locale",
        "en",
        "dateFormat",
        "yyyy-MM-dd",
        "txnDate",
        date.toString());
  }

  private static Long transactionId(CommandProcessingResult result) {
    if (result == null || result.getSubResourceId() == null || result.isRollbackTransaction())
      throw new GeneralPlatformDomainRuleException(
          "error.msg.base.teller.custody.movement.failed",
          "Fineract did not return a completed cashier transaction reference.");
    return result.getSubResourceId();
  }

  public static Long movement(
      PortfolioCommandSourceWritePlatformService commands,
      FineractRequestContextHolder requestContext,
      CommandWrapperBuilder builder,
      BigDecimal amount,
      String currency,
      LocalDate date,
      String note,
      String key) {
    Map<String, Object> body = body(amount, currency, date, note);
    // Fineract stores the last native command ID in the HTTP request. A second internal
    // command would otherwise be treated as a retry of the first one.
    String attribute = SynchronousCommandProcessingService.COMMAND_SOURCE_ID;
    Object enclosingCommandId = requestContext.getAttribute(attribute, null);
    CommandProcessingResult result;
    try {
      requestContext.setAttribute(attribute, null);
      result = commands.logCommandSource(builder.withJson(GSON.toJson(body)).build(key));
    } finally {
      requestContext.setAttribute(attribute, enclosingCommandId);
    }
    return transactionId(result);
  }
}
