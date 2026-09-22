package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;

public record CashierCheckData(Long id, String bank, String checkNumber, BigDecimal amount) {}
