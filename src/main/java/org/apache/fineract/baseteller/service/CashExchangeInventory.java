package org.apache.fineract.baseteller.service;

import static org.apache.fineract.baseteller.validation.CashExchangeValidator.invalid;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Uses one database snapshot of recorded composition. Unknown physical movements fail closed. */
@Component
@RequiredArgsConstructor
public class CashExchangeInventory {
  private final NamedParameterJdbcTemplate jdbc;
  private static final List<Source> SOURCES =
      List.of(
          new Source(
              "m_cash_allocation",
              "m_cash_allocation_denomination",
              "allocation_id",
              "h.business_date",
              "h.amount",
              "h.destination_cashier_id=:cashier",
              1,
              "false"),
          new Source(
              "m_cash_allocation",
              "m_cash_allocation_denomination",
              "allocation_id",
              "h.business_date",
              "h.amount",
              "h.source_cashier_id=:cashier",
              -1,
              "false"),
          new Source(
              "m_base_teller_deposit",
              "m_base_teller_deposit_cash_detail",
              "deposit_id",
              "(SELECT st.transaction_date FROM m_savings_account_transaction st WHERE"
                  + " st.id=h.savings_transaction_id AND NOT st.is_reversed)",
              "h.amount",
              "h.cashier_id=:cashier AND h.funding_type='CASH'",
              1,
              "false"),
          new Source(
              "m_base_teller_savings_opening",
              "m_base_teller_savings_opening_cash_detail",
              "opening_id",
              "(SELECT st.transaction_date FROM m_savings_account_transaction st WHERE"
                  + " st.id=h.initial_deposit_transaction_id AND NOT st.is_reversed)",
              "h.amount",
              "h.cashier_id=:cashier AND h.funding_type='CASH'",
              1,
              "false"),
          new Source(
              "m_service_payment",
              "m_service_payment_cash_detail",
              "service_payment_id",
              "h.business_date",
              "h.total_to_pay",
              "h.cashier_id=:cashier",
              1,
              "h.change_amount<>0"),
          new Source(
              "m_base_teller_credit_payment",
              "m_base_teller_credit_payment_cash_detail",
              "credit_payment_id",
              "h.business_date",
              "h.amount",
              "h.cashier_id=:cashier AND h.payment_method='CASH'",
              1,
              "h.change_amount<>0"),
          new Source(
              "m_base_teller_returned_check_payment",
              "m_base_teller_returned_check_payment_cash_detail",
              "settlement_id",
              "(SELECT ct.txn_date FROM m_cashier_transactions ct WHERE"
                  + " ct.id=h.cashier_transaction_id)",
              "h.cash_received-h.change_amount",
              "h.cashier_id=:cashier",
              1,
              "h.change_amount<>0"),
          new Source(
              "m_cash_operation_transaction",
              "m_cash_operation_denomination",
              "operation_id",
              "h.business_date",
              "h.cash_total",
              "h.cashier_id=:cashier",
              -1,
              "false"),
          new Source(
              "m_cash_exchange",
              "m_cash_exchange_detail",
              "exchange_id",
              "h.business_date",
              "h.received_total",
              "h.cashier_id=:cashier",
              1,
              "false",
              "d.direction='RECEIVED'"),
          new Source(
              "m_cash_exchange",
              "m_cash_exchange_detail",
              "exchange_id",
              "h.business_date",
              "h.delivered_total",
              "h.cashier_id=:cashier",
              -1,
              "false",
              "d.direction='DELIVERED'"));

  public Map<String, Long> quantities(Long cashierId, String currency, LocalDate date) {
    Map<String, Long> result = new HashMap<>();
    jdbc.query(
        sql(),
        Map.of("cashier", cashierId, "currency", currency, "date", date),
        rs -> {
          if (rs.getBoolean("closed"))
            throw invalid("drawer.closed", "The drawer is reconciled for this date and currency.");
          if (!rs.getBoolean("valid"))
            throw invalid(
                "inventory.unknown",
                "Complete, reconciled denomination composition is unavailable. Unrecorded"
                    + " movements, change payments or invalid snapshots prevent exchange.");
          if (rs.getString("identifier") != null) {
            try {
              result.put(rs.getString("identifier"), rs.getBigDecimal("quantity").longValueExact());
            } catch (ArithmeticException e) {
              throw invalid(
                  "inventory.overflow", "Recorded inventory exceeds supported integer range.");
            }
          }
        });
    return Map.copyOf(result);
  }

  private static String sql() {
    String headers = String.join(" UNION ALL ", SOURCES.stream().map(Source::headerSql).toList());
    String movements =
        String.join(" UNION ALL ", SOURCES.stream().map(Source::movementSql).toList());
    return "WITH headers AS ("
        + headers
        + "), movements AS ("
        + movements
        + "), quantities AS (SELECT identifier,SUM(quantity*sign) quantity FROM movements WHERE"
        + " quantity<>0 GROUP BY identifier), monetary AS ("
        + MONETARY
        + ") "
        + """
        SELECT q.identifier,q.quantity,
          EXISTS(SELECT 1 FROM m_cashier_reconciliation WHERE cashier_id=:cashier AND business_date=:date AND UPPER(currency_code)=:currency) closed,
          NOT EXISTS(SELECT 1 FROM headers h WHERE h.unexplained OR h.expected<>COALESCE((SELECT SUM(m.value*m.quantity) FROM movements m WHERE m.source_key=h.source_key),0))
          AND NOT EXISTS(SELECT 1 FROM movements m LEFT JOIN m_service_payment_denomination c ON c.currency_code=:currency AND c.identifier=m.identifier
            WHERE m.quantity<0 OR (m.quantity>0 AND (c.id IS NULL OR c.value<>m.value)))
          AND NOT EXISTS(SELECT 1 FROM quantities WHERE quantity<0)
          AND NOT EXISTS(
        """
        + UNKNOWN_NATIVE
        + """
          ) AND COALESCE((SELECT SUM(m.value*m.quantity*m.sign) FROM movements m),0)=(SELECT * FROM monetary) valid
        FROM (SELECT 1) sentinel LEFT JOIN quantities q ON true
        """;
  }

  private static final String UNKNOWN_NATIVE =
"""
SELECT 1 FROM m_cashier_transactions ct
            WHERE ct.cashier_id=:cashier AND ct.txn_date=:date AND UPPER(ct.currency_code)=:currency
            AND ct.txn_amount<>0
            AND NOT EXISTS (SELECT 1 FROM m_cash_allocation a WHERE a.status='COMPLETED' AND (a.source_cashier_transaction_id=ct.id OR a.destination_cashier_transaction_id=ct.id))
            AND NOT EXISTS (SELECT 1 FROM m_service_payment a WHERE a.status='COMPLETED' AND a.cashier_transaction_id=ct.id)
            AND NOT EXISTS (SELECT 1 FROM m_base_teller_credit_payment a WHERE a.status='COMPLETED' AND a.payment_method='CASH' AND a.cashier_transaction_id=ct.id)
            AND NOT EXISTS (SELECT 1 FROM m_base_teller_returned_check_payment a WHERE a.status='COMPLETED' AND a.cashier_transaction_id=ct.id)
            AND NOT EXISTS (SELECT 1 FROM m_cash_operation_transaction a WHERE a.status='COMPLETED' AND a.cashier_transaction_id=ct.id)
            AND NOT EXISTS (SELECT 1 FROM m_base_teller_deposit a WHERE a.status='COMPLETED' AND a.funding_type='CASH' AND ct.entity_type='BASE_TELLER_DEPOSIT' AND ct.entity_id=a.id)
            AND NOT EXISTS (SELECT 1 FROM m_base_teller_savings_opening a WHERE a.status='COMPLETED' AND a.funding_type='CASH' AND ct.entity_type='BASE_TELLER_SAVINGS_OPENING' AND ct.entity_id=a.id)
""";
  private static final String MONETARY =
"""
SELECT COALESCE(SUM(amount),0) FROM (
              SELECT CASE WHEN ct.txn_type IN (101,103) THEN ct.txn_amount WHEN ct.txn_type IN (102,104) THEN -ct.txn_amount ELSE 0 END amount
              FROM m_cashier_transactions ct WHERE ct.cashier_id=:cashier AND ct.txn_date=:date AND UPPER(ct.currency_code)=:currency
              UNION ALL SELECT d.amount FROM m_base_teller_deposit d JOIN m_savings_account_transaction st ON st.id=d.savings_transaction_id
              WHERE d.cashier_id=:cashier AND d.status='COMPLETED' AND d.funding_type='CASH' AND st.transaction_date=:date AND NOT st.is_reversed AND UPPER(d.currency_code)=:currency
                AND NOT EXISTS(SELECT 1 FROM m_cashier_transactions ct WHERE ct.entity_type='BASE_TELLER_DEPOSIT' AND ct.entity_id=d.id)
              UNION ALL SELECT d.amount FROM m_base_teller_savings_opening d JOIN m_savings_account_transaction st ON st.id=d.initial_deposit_transaction_id
              WHERE d.cashier_id=:cashier AND d.status='COMPLETED' AND d.funding_type='CASH' AND st.transaction_date=:date AND NOT st.is_reversed AND UPPER(d.currency_code)=:currency
                AND NOT EXISTS(SELECT 1 FROM m_cashier_transactions ct WHERE ct.entity_type='BASE_TELLER_SAVINGS_OPENING' AND ct.entity_id=d.id)
            ) movements
""";

  private record Source(
      String header,
      String detail,
      String fk,
      String day,
      String amount,
      String scope,
      int sign,
      String unexplained,
      String detailScope) {
    Source(
        String header,
        String detail,
        String fk,
        String day,
        String amount,
        String scope,
        int sign,
        String unexplained) {
      this(header, detail, fk, day, amount, scope, sign, unexplained, "true");
    }

    private String key() {
      return "CONCAT('" + header + ":" + sign + ":',h.id)";
    }

    private String where() {
      return " WHERE h.status='COMPLETED' AND "
          + day
          + "=:date AND UPPER(h.currency_code)=:currency AND "
          + scope;
    }

    String headerSql() {
      return "SELECT "
          + key()
          + " source_key,"
          + amount
          + " expected,"
          + unexplained
          + " unexplained FROM "
          + header
          + " h"
          + where();
    }

    String movementSql() {
      return "SELECT "
          + key()
          + " source_key,d.denomination_identifier identifier,d.denomination_value"
          + " value,d.quantity,"
          + sign
          + " sign FROM "
          + header
          + " h JOIN "
          + detail
          + " d ON d."
          + fk
          + "=h.id"
          + where()
          + " AND "
          + detailScope;
    }
  }
}
