package org.apache.fineract.baseteller.cashtransfer;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.apache.fineract.baseteller.data.EndOfDayClosingData.Cashier;
import org.junit.jupiter.api.Test;

class CashTransferReceiptTest {
  @Test
  void rendersSnapshotWithEscapedNamesAndSeparateCurrencyTotals() {
    var source = new Cashier(1L, 1L, "Source <script>", 1L, "Window", 1L, "Branch & office");
    var destination = new Cashier(2L, 2L, "Destination", 1L, "Window", 1L, "Branch & office");
    var line =
        new CashTransferData.Denomination(
            "note", "BANKNOTE", new BigDecimal("100"), 2, new BigDecimal("200"));
    var currencies =
        List.of(
            new CashTransferData.Currency("AAA", 2, new BigDecimal("200"), List.of(line), 10L, 11L),
            new CashTransferData.Currency(
                "BBB", 2, new BigDecimal("200"), List.of(line), 12L, 13L));
    var receipt =
        new CashTransferData(
            1L,
            "CT-reference",
            CashTransferPlatformService.OPERATION,
            "COMPLETED",
            "2026-10-16",
            source,
            destination,
            1L,
            "Actor",
            "2026-10-16T10:00:00Z",
            "2026-10-16T10:00:01Z",
            true,
            List.of(),
            List.of(),
            currencies);
    String html = CashTransferReceipt.html(receipt);
    assertTrue(html.contains("Source &lt;script&gt;"));
    assertFalse(html.contains("<script>"));
    assertTrue(html.contains("Branch &amp; office"));
    assertTrue(html.contains("Total AAA"));
    assertTrue(html.contains("Total BBB"));
    assertTrue(html.contains("CT-reference"));
    assertTrue(html.contains("2026-10-16T10:00:01Z"));
  }
}
