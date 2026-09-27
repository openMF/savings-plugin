package org.apache.fineract.baseteller.data;

import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.portfolio.paymenttype.data.PaymentTypeData;

public record CreditPaymentContextData(
    LocalDate businessDate,
    Long officeId,
    Long tellerId,
    Long cashierId,
    List<CreditPaymentDenominationData> denominations,
    List<CreditPaymentBankData> banks,
    List<PaymentTypeData> paymentTypes) {}
