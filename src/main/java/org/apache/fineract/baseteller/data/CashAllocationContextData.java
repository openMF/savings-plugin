package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CashAllocationContextData(
    LocalDate businessDate,
    Long officeId,
    String officeName,
    String currencyCode,
    BigDecimal vaultBalance,
    List<CashAllocationCurrencyData> currencies,
    List<CashAllocationCashierData> cashiers) {}
