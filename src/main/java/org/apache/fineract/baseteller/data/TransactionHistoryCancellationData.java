package org.apache.fineract.baseteller.data;

import java.time.OffsetDateTime;

public record TransactionHistoryCancellationData(String reason, String user, OffsetDateTime date) {}
