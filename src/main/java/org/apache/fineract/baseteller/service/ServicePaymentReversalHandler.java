package org.apache.fineract.baseteller.service;

import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ServicePaymentReversalHandler implements TransactionReversalHandler {
  private final JdbcTemplate jdbc;
  private final TransactionReversalCommands commands;

  public String sourceType() {
    return "SERVICE_PAYMENT";
  }

  public String accountingStrategy() {
    return "Native manual journal reversal linked to the original service journal";
  }

  public Source load(Long id) {
    var rows =
        jdbc.query(
            """
            SELECT p.*,u.username,(SELECT COUNT(*) FROM acc_gl_journal_entry j
              WHERE j.transaction_id=p.accounting_transaction_id AND j.manual_entry AND NOT j.reversed) journal_count,
              (SELECT COALESCE(SUM(j.amount),0) FROM acc_gl_journal_entry j WHERE j.transaction_id=p.accounting_transaction_id AND j.type_enum=?) debits,
              (SELECT COALESCE(SUM(j.amount),0) FROM acc_gl_journal_entry j WHERE j.transaction_id=p.accounting_transaction_id AND j.type_enum=?) credits,
              EXISTS(SELECT 1 FROM acc_gl_journal_entry j WHERE j.transaction_id=p.accounting_transaction_id AND (j.office_id<>p.office_id OR UPPER(j.currency_code)<>UPPER(p.currency_code) OR j.entry_date<>p.business_date OR NOT j.manual_entry OR j.reversed)) invalid_journal
            FROM m_service_payment p LEFT JOIN m_appuser u ON u.id=p.operator_id WHERE p.id=?
            """,
            (rs, row) -> {
              BigDecimal amount = rs.getBigDecimal("total_to_pay");
              return new Source(
                  id,
                  rs.getLong("office_id"),
                  rs.getLong("cashier_id"),
                  null,
                  null,
                  rs.getObject("business_date", java.time.LocalDate.class),
                  rs.getString("status"),
                  rs.getString("currency_code").toUpperCase(java.util.Locale.ROOT),
                  amount,
                  amount,
                  BigDecimal.ZERO,
                  "IN",
                  rs.getString("username"),
                  "PAY_SERVICE",
                  rs.getInt("journal_count") < 2
                          || rs.getBoolean("invalid_journal")
                          || amount.compareTo(rs.getBigDecimal("debits")) != 0
                          || amount.compareTo(rs.getBigDecimal("credits")) != 0
                      ? "The original manual journal is missing or already reversed."
                      : null,
                  List.of(),
                  List.of());
            },
            org.apache.fineract.accounting.journalentry.domain.JournalEntryType.DEBIT.getValue(),
            org.apache.fineract.accounting.journalentry.domain.JournalEntryType.CREDIT.getValue(),
            id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public String reverse(Source source, String key) {
    String original =
        jdbc.queryForObject(
            "SELECT accounting_transaction_id FROM m_service_payment WHERE id=?",
            String.class,
            source.id());
    var result =
        commands.execute(
            new CommandWrapperBuilder().reverseJournalEntry(original),
            "{\"comments\":\"WEB-1259 service payment reversal\"}",
            key + ":journal-undo");
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT COUNT(*)>1 AND BOOL_AND(reversed) FROM acc_gl_journal_entry WHERE"
                + " transaction_id=?",
            Boolean.class,
            original)))
      throw TransactionReversalPlatformService.invalid(
          "native.not.reversed", "Native journal reversal did not reverse the original.");
    return result.getTransactionId();
  }
}
