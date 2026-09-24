package org.apache.fineract.baseteller.data;

import java.math.BigDecimal;
import java.util.List;

public record ServicePaymentServiceData(
    Long id,
    String code,
    String name,
    boolean active,
    String currencyCode,
    ServicePaymentCommissionType commissionType,
    BigDecimal commissionValue,
    BigDecimal commissionVatRate,
    List<ServicePaymentDenominationData> denominations) {}
