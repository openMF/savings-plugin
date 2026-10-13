package org.apache.fineract.baseteller.service;

import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.baseteller.data.TransactionReversalData.Check;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CreditPaymentReversalHandler implements TransactionReversalHandler {
  private final JdbcTemplate jdbc;
  private final TransactionReversalCommands commands;

  public String sourceType() {
    return "CREDIT_PAYMENT";
  }

  public String accountingStrategy() {
    return "Native loan repayment undo through zero adjustment; native schedule and journal"
        + " recalculation";
  }

  public Source load(Long id) {
    var checks =
        jdbc.query(
            """
            SELECT c.id,c.bank_id,c.bank_name_snapshot,c.check_number,p.amount,UPPER(p.currency_code),
              c.status='CLEARED' AND b.active AND NOT EXISTS(SELECT 1 FROM m_end_of_day_closing_check x WHERE x.source_type='CREDIT' AND x.source_id=c.id) eligible
            FROM m_base_teller_credit_payment_check c JOIN m_base_teller_credit_payment p ON p.id=c.credit_payment_id
            JOIN m_base_teller_bank b ON b.id=c.bank_id WHERE p.id=? ORDER BY c.id
            """,
            (rs, row) ->
                new Check(
                    rs.getLong(1),
                    rs.getLong(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getBigDecimal(5),
                    rs.getString(6).toUpperCase(java.util.Locale.ROOT),
                    rs.getBoolean(7)),
            id);
    var rows =
        jdbc.query(
            """
            SELECT p.*,lt.is_reversed,lt.transaction_date,lt.amount native_amount,lt.transaction_type_enum native_type,l.currency_code account_currency,l.loan_status_id,cl.status_enum client_status,cl.office_id current_office,lt.office_id original_office,u.username,
              EXISTS(SELECT 1 FROM acc_gl_closure c WHERE c.office_id=lt.office_id AND c.closing_date>=lt.transaction_date) accounting_closed,
              EXISTS(SELECT 1 FROM m_loan_transaction later WHERE later.loan_id=l.id AND NOT later.is_reversed AND later.id>lt.id) dependent
            FROM m_base_teller_credit_payment p JOIN m_loan l ON l.id=p.loan_id AND l.client_id=p.client_id JOIN m_client cl ON cl.id=p.client_id
            LEFT JOIN m_loan_transaction lt ON lt.id=p.loan_transaction_id AND lt.loan_id=p.loan_id LEFT JOIN m_appuser u ON u.id=p.operator_id WHERE p.id=?
            """,
            (rs, row) -> {
              boolean cash = "CASH".equals(rs.getString("payment_method"));
              BigDecimal amount = rs.getBigDecimal("amount");
              String reason =
                  rs.getObject("transaction_date") == null
                      ? "The repayment has not posted."
                      : rs.getInt("native_type")
                              != org.apache.fineract.portfolio.loanaccount.domain
                                  .LoanTransactionType.REPAYMENT
                                  .getValue()
                          ? "The native transaction is not a loan repayment."
                          : !rs.getObject("business_date", java.time.LocalDate.class)
                                  .equals(
                                      rs.getObject("transaction_date", java.time.LocalDate.class))
                              ? "The repayment posted on a different business date."
                              : rs.getBoolean("is_reversed")
                                  ? "The native repayment is already reversed."
                                  : rs.getInt("loan_status_id")
                                          != org.apache.fineract.portfolio.loanaccount.domain
                                              .LoanStatus.ACTIVE
                                              .getValue()
                                      ? "The loan is not active."
                                      : rs.getInt("client_status")
                                              != org.apache.fineract.portfolio.client.domain
                                                  .ClientStatus.ACTIVE
                                                  .getValue()
                                          ? "The client is not active."
                                          : rs.getLong("current_office")
                                                      != rs.getLong("original_office")
                                                  || rs.getLong("office_id")
                                                      != rs.getLong("original_office")
                                              ? "The original and current offices do not reconcile."
                                              : rs.getBoolean("accounting_closed")
                                                  ? "The original accounting period is closed."
                                                  : rs.getBoolean("dependent")
                                                      ? "Later loan transactions prevent reversal."
                                                      : amount.compareTo(
                                                                      rs.getBigDecimal(
                                                                          "native_amount"))
                                                                  != 0
                                                              || !rs.getString("currency_code")
                                                                  .equalsIgnoreCase(
                                                                      rs.getString(
                                                                          "account_currency"))
                                                          ? "Source amounts or currencies do not"
                                                                + " reconcile."
                                                          : !cash
                                                                  && (checks.size() != 1
                                                                      || !checks
                                                                          .getFirst()
                                                                          .eligible())
                                                              ? "The original check is not"
                                                                    + " eligible."
                                                              : null;
              return new Source(
                  id,
                  rs.getLong("office_id"),
                  rs.getObject("cashier_id", Long.class),
                  rs.getLong("loan_id"),
                  rs.getObject("loan_transaction_id", Long.class),
                  rs.getObject("business_date", java.time.LocalDate.class),
                  rs.getString("status"),
                  rs.getString("currency_code").toUpperCase(java.util.Locale.ROOT),
                  amount,
                  cash ? amount : BigDecimal.ZERO,
                  cash ? BigDecimal.ZERO : amount,
                  "IN",
                  rs.getString("username"),
                  "PAY_CREDIT",
                  reason,
                  checks,
                  List.of());
            },
            id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public String reverse(Source source, String key) {
    String json =
        "{\"transactionDate\":\""
            + source.businessDate()
            + "\",\"transactionAmount\":0,\"dateFormat\":\"yyyy-MM-dd\",\"locale\":\"en\"}";
    commands.execute(
        new CommandWrapperBuilder().adjustTransaction(source.accountId(), source.transactionId()),
        json,
        key + ":loan-undo");
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT is_reversed FROM m_loan_transaction WHERE id=?",
            Boolean.class,
            source.transactionId())))
      throw TransactionReversalPlatformService.invalid(
          "native.not.reversed", "Native repayment undo did not reverse the original.");
    return "LOAN_TRANSACTION:" + source.transactionId();
  }
}
