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
public class SavingsDepositReversalHandler implements TransactionReversalHandler {
  private final JdbcTemplate jdbc;
  private final TransactionReversalCommands commands;

  public String sourceType() {
    return "SAVINGS_DEPOSIT";
  }

  public String accountingStrategy() {
    return "Native savings undo, including original journal reversal and account recalculation";
  }

  public Source load(Long id) {
    List<Check> checks =
        jdbc.query(
            """
            SELECT cd.id,b.id bank_id,cd.check_bank,cd.check_number,cd.amount,UPPER(d.currency_code) currency_code,
              cd.clearing_status='CLEARED' AND NOT EXISTS(SELECT 1 FROM m_base_teller_returned_check r WHERE r.deposit_check_detail_id=cd.id)
              AND NOT EXISTS(SELECT 1 FROM m_cash_operation_check c WHERE c.deposit_check_detail_id=cd.id)
              AND NOT EXISTS(SELECT 1 FROM m_cashier_reconciliation_check c WHERE c.deposit_check_detail_id=cd.id) eligible
            FROM m_base_teller_deposit_check_detail cd JOIN m_base_teller_deposit d ON d.id=cd.deposit_id
            LEFT JOIN m_base_teller_bank b ON b.name=cd.check_bank AND b.active AND (SELECT COUNT(*) FROM m_base_teller_bank unique_bank WHERE unique_bank.name=cd.check_bank AND unique_bank.active)=1
            WHERE d.id=? ORDER BY cd.id
            """,
            (rs, row) ->
                new Check(
                    rs.getLong("id"),
                    rs.getObject("bank_id", Long.class),
                    rs.getString("check_bank"),
                    rs.getString("check_number"),
                    rs.getBigDecimal("amount"),
                    rs.getString("currency_code"),
                    rs.getBoolean("eligible")),
            id);
    var rows =
        jdbc.query(
            """
            SELECT d.*,st.transaction_date,st.is_reversed,a.status_enum,a.currency_code account_currency,
              st.amount native_amount,st.transaction_type_enum native_type,cl.status_enum client_status,cl.office_id current_office,st.office_id original_office,u.username,
              EXISTS(SELECT 1 FROM acc_gl_closure c WHERE c.office_id=st.office_id AND c.closing_date>=st.transaction_date) accounting_closed,
              EXISTS(SELECT 1 FROM m_savings_account_transaction later WHERE later.savings_account_id=a.id
                AND NOT later.is_reversed AND later.id>st.id) dependent,
              EXISTS(SELECT 1 FROM m_account_transfer_transaction t WHERE t.from_savings_transaction_id=st.id OR t.to_savings_transaction_id=st.id) transferred
            FROM m_base_teller_deposit d LEFT JOIN m_savings_account_transaction st ON st.id=d.savings_transaction_id AND st.savings_account_id=d.savings_account_id
            JOIN m_savings_account a ON a.id=d.savings_account_id AND a.client_id=d.client_id JOIN m_client cl ON cl.id=d.client_id LEFT JOIN m_appuser u ON u.id=d.operator_id WHERE d.id=?
            """,
            (rs, row) -> {
              boolean cash = "CASH".equals(rs.getString("funding_type"));
              BigDecimal amount = rs.getBigDecimal("amount");
              String reason =
                  rs.getObject("transaction_date") == null
                      ? "The deposit has not posted."
                      : rs.getInt("native_type")
                              != org.apache.fineract.portfolio.savings.SavingsAccountTransactionType
                                  .DEPOSIT
                                  .getValue()
                          ? "The native transaction is not a savings deposit."
                          : rs.getBoolean("is_reversed")
                              ? "The native transaction is already reversed."
                              : rs.getInt("status_enum")
                                      != org.apache.fineract.portfolio.savings.domain
                                          .SavingsAccountStatusType.ACTIVE
                                          .getValue()
                                  ? "The savings account is not active."
                                  : rs.getInt("client_status")
                                          != org.apache.fineract.portfolio.client.domain
                                              .ClientStatus.ACTIVE
                                              .getValue()
                                      ? "The client is not active."
                                      : rs.getBoolean("dependent") || rs.getBoolean("transferred")
                                          ? "Dependent account transactions prevent reversal."
                                          : rs.getLong("current_office")
                                                  != rs.getLong("original_office")
                                              ? "The account was transferred after the original"
                                                    + " transaction."
                                              : rs.getBoolean("accounting_closed")
                                                  ? "The savings accounting period is closed."
                                                  : amount.compareTo(
                                                                  rs.getBigDecimal("native_amount"))
                                                              != 0
                                                          || !rs.getString("currency_code")
                                                              .equalsIgnoreCase(
                                                                  rs.getString("account_currency"))
                                                      ? "Source amounts or currencies do not"
                                                            + " reconcile."
                                                      : !cash
                                                              && (checks.isEmpty()
                                                                  || checks.stream()
                                                                      .anyMatch(
                                                                          c ->
                                                                              !c.eligible()
                                                                                  || c.bankId()
                                                                                      == null))
                                                          ? "Original checks are not eligible or"
                                                                + " lack a unique bank catalog"
                                                                + " mapping."
                                                          : null;
              return new Source(
                  id,
                  rs.getObject("office_id", Long.class),
                  rs.getObject("cashier_id", Long.class),
                  rs.getLong("savings_account_id"),
                  rs.getObject("savings_transaction_id", Long.class),
                  rs.getObject("transaction_date", java.time.LocalDate.class),
                  rs.getString("status"),
                  rs.getString("currency_code").toUpperCase(java.util.Locale.ROOT),
                  amount,
                  cash ? amount : BigDecimal.ZERO,
                  cash ? BigDecimal.ZERO : amount,
                  "IN",
                  rs.getString("username"),
                  "SAVINGS_DEPOSIT",
                  reason,
                  checks,
                  List.of());
            },
            id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public String reverse(Source source, String key) {
    commands.execute(
        new CommandWrapperBuilder()
            .undoSavingsAccountTransaction(source.accountId(), source.transactionId()),
        "{}",
        key + ":savings-undo");
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT is_reversed FROM m_savings_account_transaction WHERE id=?",
            Boolean.class,
            source.transactionId())))
      throw TransactionReversalPlatformService.invalid(
          "native.not.reversed", "Native savings undo did not reverse the original.");
    return "SAVINGS_TRANSACTION:" + source.transactionId();
  }
}
