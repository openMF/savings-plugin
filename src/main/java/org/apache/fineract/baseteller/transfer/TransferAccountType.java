package org.apache.fineract.baseteller.transfer;

import org.apache.fineract.portfolio.account.PortfolioAccountType;

public enum TransferAccountType {
  SAVINGS(PortfolioAccountType.SAVINGS),
  LOAN(PortfolioAccountType.LOAN);

  private final PortfolioAccountType nativeType;

  TransferAccountType(PortfolioAccountType nativeType) {
    this.nativeType = nativeType;
  }

  public Integer nativeValue() {
    return nativeType.getValue();
  }
}
