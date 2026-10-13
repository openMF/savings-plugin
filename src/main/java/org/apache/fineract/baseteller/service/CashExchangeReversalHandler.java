package org.apache.fineract.baseteller.service;

import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.baseteller.data.BaseTellerDenominationData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** WEB-1257 owns this physical swap and intentionally posts no financial transaction. */
@Component
@RequiredArgsConstructor
public class CashExchangeReversalHandler implements TransactionReversalHandler {
  private final JdbcTemplate jdbc;

  public String sourceType() {
    return "CASH_EXCHANGE";
  }

  public String accountingStrategy() {
    return "Inverse physical denomination swap; original exchange has no accounting movement";
  }

  public Source load(Long id) {
    var delivered =
        jdbc.query(
            "SELECT denomination_identifier,denomination_value,quantity FROM m_cash_exchange_detail"
                + " WHERE exchange_id=? AND direction='DELIVERED' ORDER BY denomination_identifier",
            (rs, row) ->
                new BaseTellerDenominationData(rs.getString(1), rs.getBigDecimal(2), rs.getLong(3)),
            id);
    var rows =
        jdbc.query(
            "SELECT * FROM m_cash_exchange WHERE id=?",
            (rs, row) ->
                new Source(
                    id,
                    rs.getLong("office_id"),
                    rs.getLong("cashier_id"),
                    null,
                    null,
                    rs.getObject("business_date", java.time.LocalDate.class),
                    rs.getString("status"),
                    rs.getString("currency_code").toUpperCase(java.util.Locale.ROOT),
                    rs.getBigDecimal("received_total"),
                    rs.getBigDecimal("received_total"),
                    BigDecimal.ZERO,
                    "SWAP",
                    rs.getString("created_by_username"),
                    "CASH_EXCHANGE",
                    null,
                    List.of(),
                    delivered),
            id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  @Override
  public void validatePhysicalComponents(
      Source source,
      List<BaseTellerDenominationData> removed,
      List<BaseTellerDenominationData> returned) {
    var received =
        jdbc.query(
            "SELECT denomination_identifier,denomination_value,quantity FROM m_cash_exchange_detail"
                + " WHERE exchange_id=? AND direction='RECEIVED' ORDER BY denomination_identifier",
            (rs, row) ->
                new BaseTellerDenominationData(rs.getString(1), rs.getBigDecimal(2), rs.getLong(3)),
            source.id());
    if (!quantities(received).equals(quantities(removed))
        || !quantities(source.returnedDenominations()).equals(quantities(returned)))
      throw TransactionReversalPlatformService.invalid(
          "exchange.breakdown",
          "Exchange reversal must remove the original received composition and return the original"
              + " delivered composition.");
  }

  private java.util.Map<String, Long> quantities(List<BaseTellerDenominationData> lines) {
    java.util.Map<String, Long> result = new java.util.HashMap<>();
    lines.forEach(line -> result.put(line.denominationId(), line.quantity()));
    return result;
  }

  public String reverse(Source source, String key) {
    if (jdbc.update(
            "UPDATE m_cash_exchange SET status='REVERSED' WHERE id=? AND status='COMPLETED'",
            source.id())
        != 1)
      throw TransactionReversalPlatformService.invalid(
          "source.ineligible", "The original exchange is already reversed.");
    return "CASH_EXCHANGE:" + source.id();
  }
}
