package org.apache.fineract.baseteller.withdrawal;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.fineract.baseteller.data.EndOfDayClosingData.Cashier;
import org.junit.jupiter.api.Test;

class WithdrawalReceiptTest {
  @Test
  void historicalReceiptEscapesEveryTextFieldAndShowsOnlyMaskedIdentity() {
    String attack = "<script>alert('x')</script>";
    var r =
        new WithdrawalData(
            1L,
            "WD-test",
            "COMPLETED",
            "2026-10-17",
            "2026-10-17T12:00Z",
            new WithdrawalData.Customer(
                2L, "client", attack, "Person", "Active", 1L, "office", true),
            new WithdrawalData.Account(
                3L, "account", 4L, "product", "AAA", 2, "ACTIVE", null, false, List.of()),
            new Cashier(5L, 6L, attack, 7L, "teller", 1L, attack),
            8L,
            attack,
            WithdrawalRequest.Method.CASH,
            new BigDecimal("100"),
            "AAA",
            List.of(
                new WithdrawalData.Denomination(
                    "note", "AAA", "BANKNOTE", new BigDecimal("50"), 2L, new BigDecimal("100"))),
            new WithdrawalData.Identification(9L, 10L, attack, "****1234"),
            new BigDecimal("100"),
            new BigDecimal("100"),
            Map.of(),
            Map.of("AAA", new BigDecimal("100")),
            11L,
            12L,
            false);
    String html = WithdrawalReceipt.html(r);
    assertTrue(html.contains("WD-test"));
    assertTrue(html.contains("****1234"));
    assertTrue(html.contains("&lt;script&gt;"));
    assertFalse(html.contains("<script>"));
    assertTrue(html.contains("<table>"));
    assertEquals(html, WithdrawalReceipt.html(r));
  }
}
