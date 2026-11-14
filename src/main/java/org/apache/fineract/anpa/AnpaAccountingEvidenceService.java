/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.anpa;

import static org.apache.fineract.anpa.AnpaAccountingData.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.loanaccount.exception.LoanNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Exact bc1c598 schema; no financial write service or command gateway is injected here. */
@Component
@RequiredArgsConstructor
public class AnpaAccountingEvidenceService {
  static final int LIMIT = 10000;
  private final JdbcTemplate jdbc;
  private final AnpaAccountingAccess access;

  Loan loan(long id) {
    if (id <= 0) throw AnpaSupport.invalid("loanId", "Positive loan ID required.");
    var rows =
        jdbc.query(
            """
        SELECT l.id,l.client_id,l.group_id,l.product_id,o.id office_id,l.loan_status_id,
          l.is_npa,l.is_charged_off,p.accounting_type,l.currency_code,l.currency_digits,
          l.submittedon_date,l.disbursedon_date,l.writtenoffon_date,
          l.principal_outstanding_derived,l.interest_outstanding_derived,
          l.fee_charges_outstanding_derived,l.penalty_charges_outstanding_derived,l.total_outstanding_derived
        FROM m_loan l JOIN m_product_loan p ON p.id=l.product_id
          LEFT JOIN m_client c ON c.id=l.client_id LEFT JOIN m_group g ON g.id=l.group_id
          JOIN m_office o ON o.id=COALESCE(c.office_id,g.office_id)
          LEFT JOIN m_office gof ON gof.id=g.office_id
        WHERE l.id=? AND o.hierarchy LIKE ?
          AND (l.group_id IS NULL OR gof.hierarchy LIKE ?)
        """,
            (r, n) ->
                new Loan(
                    r.getLong("id"),
                    number(r, "client_id"),
                    number(r, "group_id"),
                    r.getLong("product_id"),
                    r.getLong("office_id"),
                    r.getInt("loan_status_id"),
                    r.getBoolean("is_npa"),
                    r.getBoolean("is_charged_off"),
                    r.getInt("accounting_type"),
                    r.getString("currency_code"),
                    r.getInt("currency_digits"),
                    day(r, "submittedon_date"),
                    day(r, "disbursedon_date"),
                    day(r, "writtenoffon_date"),
                    DateUtils.getBusinessLocalDate(),
                    new Balances(
                        r.getBigDecimal("principal_outstanding_derived"),
                        r.getBigDecimal("interest_outstanding_derived"),
                        r.getBigDecimal("fee_charges_outstanding_derived"),
                        r.getBigDecimal("penalty_charges_outstanding_derived"),
                        r.getBigDecimal("total_outstanding_derived"))),
            id,
            access.hierarchy(),
            access.hierarchy());
    if (rows.size() != 1) throw new LoanNotFoundException(id);
    return rows.getFirst();
  }

  Evidence collect(Loan loan, LocalDate asOf) {
    var mappings =
        jdbc.query(
            """
        SELECT p.*,g.classification_enum,g.account_usage,g.disabled FROM acc_product_mapping p
          LEFT JOIN acc_gl_account g ON g.id=p.gl_account_id
        WHERE p.product_id=? AND p.product_type=1 ORDER BY p.id LIMIT ?
        """,
            (r, n) ->
                new Mapping(
                    r.getLong("id"),
                    r.getInt("financial_account_type"),
                    number(r, "gl_account_id"),
                    number(r, "charge_id"),
                    number(r, "payment_type"),
                    number(r, "charge_off_reason_id"),
                    number(r, "write_off_reason_id"),
                    number(r, "capitalized_income_classification_id"),
                    number(r, "buydown_fee_classification_id"),
                    integer(r, "classification_enum"),
                    integer(r, "account_usage"),
                    r.getObject("disabled", Boolean.class)),
            loan.productId(),
            LIMIT + 1);
    var transactions =
        jdbc.query(
            """
        SELECT id,transaction_type_enum,transaction_date,submitted_on_date,is_reversed,amount,
          principal_portion_derived,interest_portion_derived,fee_charges_portion_derived,penalty_charges_portion_derived,
          reversed_on_date,created_on_utc,last_modified_on_utc
        FROM m_loan_transaction WHERE loan_id=? AND transaction_date<=? ORDER BY id LIMIT ?
        """,
            (r, n) ->
                new Transaction(
                    r.getLong(1),
                    r.getInt(2),
                    day(r, 3),
                    day(r, 4),
                    r.getBoolean(5),
                    r.getBigDecimal(6),
                    r.getBigDecimal(7),
                    r.getBigDecimal(8),
                    r.getBigDecimal(9),
                    r.getBigDecimal(10),
                    day(r, 11),
                    instant(r, 12),
                    instant(r, 13)),
            loan.loanId(),
            asOf,
            LIMIT + 1);
    // entity_id/entity_type includes manual loan-attributed postings; never join on the textual
    // L... reference.
    var journals =
        jdbc.query(
            """
        SELECT j.id,j.loan_transaction_id,j.transaction_id,j.account_id,j.entry_date,j.type_enum,j.amount,
          j.currency_code,j.reversed,j.reversal_id,j.manual_entry,j.submitted_on_date,j.created_on_utc,j.last_modified_on_utc
        FROM acc_gl_journal_entry j WHERE j.entry_date<=? AND
          ((j.entity_id=? AND j.entity_type_enum=1) OR EXISTS
            (SELECT 1 FROM m_loan_transaction t WHERE t.id=j.loan_transaction_id AND t.loan_id=?))
        ORDER BY j.id LIMIT ?
        """,
            (r, n) ->
                new Journal(
                    r.getLong(1),
                    r.getObject(2, Long.class),
                    r.getString(3),
                    r.getLong(4),
                    day(r, 5),
                    r.getInt(6),
                    r.getBigDecimal(7),
                    r.getString(8),
                    r.getBoolean(9),
                    r.getObject(10, Long.class),
                    r.getBoolean(11),
                    day(r, 12),
                    instant(r, 13),
                    instant(r, 14)),
            asOf,
            loan.loanId(),
            loan.loanId(),
            LIMIT + 1);
    var relations =
        jdbc.query(
            """
        SELECT r.id,r.from_loan_transaction_id,r.to_loan_transaction_id,r.to_loan_charge_id,r.relation_type_enum
        FROM m_loan_transaction_relation r JOIN m_loan_transaction t ON t.id=r.from_loan_transaction_id
        WHERE t.loan_id=? ORDER BY r.id LIMIT ?
        """,
            (r, n) ->
                new Relation(
                    r.getLong(1),
                    r.getLong(2),
                    r.getObject(3, Long.class),
                    r.getObject(4, Long.class),
                    r.getInt(5)),
            loan.loanId(),
            LIMIT + 1);
    var charges =
        jdbc.query(
            """
        SELECT c.id,c.charge_id,c.is_penalty,c.is_active,c.waived,c.amount,c.amount_paid_derived,
          c.amount_waived_derived,c.amount_writtenoff_derived,c.amount_outstanding_derived,c.tax_amount,d.currency_code
        FROM m_loan_charge c JOIN m_charge d ON d.id=c.charge_id WHERE c.loan_id=? ORDER BY c.id LIMIT ?
        """,
            (r, n) ->
                new Charge(
                    r.getLong(1),
                    r.getLong(2),
                    r.getBoolean(3),
                    r.getBoolean(4),
                    r.getBoolean(5),
                    r.getBigDecimal(6),
                    r.getBigDecimal(7),
                    r.getBigDecimal(8),
                    r.getBigDecimal(9),
                    r.getBigDecimal(10),
                    r.getBigDecimal(11),
                    r.getString(12)),
            loan.loanId(),
            LIMIT + 1);
    var paid =
        jdbc.query(
            """
        SELECT p.id,p.loan_transaction_id,p.loan_charge_id,p.amount FROM m_loan_charge_paid_by p
          JOIN m_loan_transaction t ON t.id=p.loan_transaction_id
        WHERE t.loan_id=? AND t.transaction_date<=? ORDER BY p.id LIMIT ?
        """,
            (r, n) ->
                new ChargePayment(r.getLong(1), r.getLong(2), r.getLong(3), r.getBigDecimal(4)),
            loan.loanId(),
            asOf,
            LIMIT + 1);
    var taxes =
        jdbc.query(
            """
        SELECT d.id,d.loan_charge_id,d.tax_component_id,d.amount,t.debit_account_id,t.credit_account_id
        FROM m_loan_charge_tax_details d JOIN m_loan_charge c ON c.id=d.loan_charge_id
          JOIN m_tax_component t ON t.id=d.tax_component_id WHERE c.loan_id=? ORDER BY d.id LIMIT ?
        """,
            (r, n) ->
                new Tax(
                    r.getLong(1),
                    r.getLong(2),
                    r.getLong(3),
                    r.getBigDecimal(4),
                    r.getObject(5, Long.class),
                    r.getObject(6, Long.class)),
            loan.loanId(),
            LIMIT + 1);
    boolean truncated =
        List.of(mappings, transactions, journals, relations, charges, paid, taxes).stream()
            .anyMatch(l -> l.size() > LIMIT);
    return new Evidence(
        loan,
        List.copyOf(mappings),
        List.copyOf(transactions),
        List.copyOf(relations),
        List.copyOf(journals),
        List.copyOf(charges),
        List.copyOf(paid),
        List.copyOf(taxes),
        truncated);
  }

  private static java.time.Instant instant(ResultSet r, int index) throws SQLException {
    var v = r.getObject(index, java.time.OffsetDateTime.class);
    return v == null ? null : v.toInstant();
  }

  private static Long number(ResultSet r, String name) throws SQLException {
    long v = r.getLong(name);
    return r.wasNull() ? null : v;
  }

  private static Integer integer(ResultSet r, String name) throws SQLException {
    int v = r.getInt(name);
    return r.wasNull() ? null : v;
  }

  private static LocalDate day(ResultSet r, String name) throws SQLException {
    var d = r.getDate(name);
    return d == null ? null : d.toLocalDate();
  }

  private static LocalDate day(ResultSet r, int index) throws SQLException {
    var d = r.getDate(index);
    return d == null ? null : d.toLocalDate();
  }
}
