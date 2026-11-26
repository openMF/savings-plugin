package org.apache.fineract.baseteller.data;

/** Closing eligibility only; shared cash-operation statuses retain their existing contract. */
public enum CashierClosingStatus {
  NOT_OPENED,
  OPEN,
  COMPLETED
}
