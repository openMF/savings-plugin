package org.apache.fineract.baseteller.exception;

import org.apache.fineract.infrastructure.core.exception.AbstractPlatformResourceNotFoundException;

public class CashExchangeNotFoundException extends AbstractPlatformResourceNotFoundException {
  public CashExchangeNotFoundException() {
    super("error.msg.base.teller.cash.exchange.not.found", "Cash exchange was not found.");
  }
}
