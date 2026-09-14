package org.apache.fineract.baseteller.data;

import java.util.List;

public record BaseTellerCustomerPositionData(
    BaseTellerCustomerData customer,
    List<BaseTellerAccountSummaryData> savingsAccounts,
    List<BaseTellerAccountSummaryData> loanAccounts) {}
