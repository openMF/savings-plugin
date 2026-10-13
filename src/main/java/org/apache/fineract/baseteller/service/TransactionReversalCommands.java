package org.apache.fineract.baseteller.service;

import lombok.RequiredArgsConstructor;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.commands.service.SynchronousCommandProcessingService;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.domain.BatchRequestContextHolder;
import org.apache.fineract.infrastructure.core.domain.FineractRequestContextHolder;
import org.springframework.stereotype.Component;

/** Native command execution joins the surrounding transaction, preserving permissions/checker. */
@Component
@RequiredArgsConstructor
public class TransactionReversalCommands {
  private final PortfolioCommandSourceWritePlatformService commands;
  private final FineractRequestContextHolder context;

  public CommandProcessingResult execute(CommandWrapperBuilder builder, String json, String key) {
    String command = SynchronousCommandProcessingService.COMMAND_SOURCE_ID;
    String store = SynchronousCommandProcessingService.IDEMPOTENCY_KEY_STORE_FLAG;
    Object previousCommand = context.getAttribute(command);
    Object previousStore = context.getAttribute(store);
    boolean previousEnclosing = BatchRequestContextHolder.isEnclosingTransaction();
    try {
      BatchRequestContextHolder.setIsEnclosingTransaction(true);
      context.setAttribute(command, null);
      context.setAttribute(store, false);
      CommandProcessingResult result = commands.logCommandSource(builder.withJson(json).build(key));
      if (result == null
          || result.getCommandId() != null
              && result.getResourceId() == null
              && result.getTransactionId() == null
              && result.getSavingsId() == null)
        throw TransactionReversalPlatformService.invalid(
            "native.not.posted", "Native reversal must complete synchronously.");
      return result;
    } finally {
      BatchRequestContextHolder.setIsEnclosingTransaction(previousEnclosing);
      context.setAttribute(command, previousCommand);
      context.setAttribute(store, previousStore);
    }
  }
}
