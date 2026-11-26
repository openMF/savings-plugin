package org.apache.fineract.baseteller.service;

import org.springframework.jdbc.core.JdbcTemplate;

/** Minimal native tables shared by database closing tests; financial tables use real migrations. */
final class CashManagementTestSchema {
  static void create(JdbcTemplate jdbc) {
    jdbc.execute("DROP SCHEMA public CASCADE");
    jdbc.execute("CREATE SCHEMA public");
    jdbc.execute(
        "CREATE TABLE m_base_teller_withdrawal(id bigint PRIMARY KEY,cashier_id bigint,currency_code varchar(3),amount numeric(19,6),method varchar(10),status varchar(20),business_date date)");
    jdbc.execute(
        "CREATE TABLE m_cash_drawer_pickup(id bigint PRIMARY KEY,business_date date,status varchar(20))");
    jdbc.execute(
        "CREATE TABLE m_cash_teller_transfer_currency(id bigint PRIMARY KEY,transfer_id bigint,currency_code varchar(3),business_date date,status varchar(20),source_cashier_id bigint,destination_cashier_id bigint,cash_total numeric(19,6),source_cashier_transaction_id bigint,destination_cashier_transaction_id bigint)");
    jdbc.execute(
        "CREATE TABLE m_cash_teller_transfer_denomination(id bigint PRIMARY KEY,transfer_currency_id bigint,denomination_identifier varchar(100),denomination_type varchar(10),denomination_value numeric(19,6),quantity bigint,line_total numeric(19,6))");
    jdbc.execute(
        "CREATE TABLE m_cash_drawer_pickup_currency(id bigint PRIMARY KEY,pickup_id bigint,currency_code varchar(3),source_cashier_transaction_id bigint,destination_cashier_transaction_id bigint)");
    jdbc.execute(
        "CREATE TABLE m_cash_drawer_pickup_check(id bigint PRIMARY KEY,pickup_currency_id bigint,source_type varchar(10),source_id bigint,source_cashier_id bigint,destination_cashier_id bigint,amount numeric(19,6))");
    jdbc.execute("CREATE TABLE m_cash_drawer_pickup_cash(id bigint PRIMARY KEY)");
    jdbc.execute(
        "CREATE TABLE m_transaction_reversal(id bigint PRIMARY KEY,cashier_id bigint,business_date"
            + " date,currency_code varchar(3),cash_effect numeric(19,6),check_effect"
            + " numeric(19,6),reversed_at timestamp)");
    jdbc.execute(
        "CREATE TABLE m_transaction_reversal_check(id bigint,source_type"
            + " varchar(50),source_check_id bigint)");
    jdbc.execute("CREATE TABLE m_transaction_reversal_denomination(id bigint)");
    jdbc.execute("CREATE TABLE m_cash_exchange(id bigint)");
    jdbc.execute("CREATE TABLE m_cash_exchange_detail(id bigint)");
    jdbc.execute("CREATE TABLE m_cash_exchange_mutex(id bigint PRIMARY KEY)");
    jdbc.update("INSERT INTO m_cash_exchange_mutex VALUES(1)");
    jdbc.execute(
        "CREATE TABLE m_office(id BIGINT PRIMARY KEY,name VARCHAR(100),hierarchy VARCHAR(100))");
    jdbc.execute("CREATE TABLE m_staff(id BIGINT PRIMARY KEY,display_name VARCHAR(100))");
    jdbc.execute(
        "CREATE TABLE m_tellers(id BIGINT PRIMARY KEY,office_id BIGINT,name VARCHAR(100),"
            + "state INT,valid_from DATE,valid_to DATE)");
    jdbc.execute(
        "CREATE TABLE m_cashiers(id BIGINT PRIMARY KEY,teller_id BIGINT,staff_id BIGINT,"
            + "start_date DATE,end_date DATE)");
    jdbc.execute(
        "CREATE TABLE m_appuser(id BIGINT PRIMARY KEY,staff_id BIGINT,username VARCHAR(100))");
    jdbc.execute(
        "CREATE TABLE m_organisation_currency(code VARCHAR(3),name VARCHAR(100),decimal_places"
            + " INT)");
    jdbc.execute(
        "CREATE TABLE m_cashier_transactions(id BIGINT PRIMARY KEY,cashier_id BIGINT,txn_type INT,"
            + "txn_date DATE,txn_amount NUMERIC(19,6),created_date TIMESTAMP,txn_note VARCHAR(100),"
            + "entity_type VARCHAR(100),entity_id BIGINT,currency_code VARCHAR(3))");
    jdbc.execute(
        "CREATE TABLE m_cash_allocation(id BIGINT PRIMARY KEY,operation_type VARCHAR(40),"
            + "status VARCHAR(20),business_date DATE,office_id BIGINT,currency_code VARCHAR(3),"
            + "amount NUMERIC(19,6),source_cashier_transaction_id BIGINT,"
            + "destination_cashier_transaction_id BIGINT,destination_cashier_id BIGINT)");
    jdbc.execute(
        "CREATE TABLE m_cash_operation_transaction(id BIGINT PRIMARY KEY,transaction_type"
            + " VARCHAR(30),business_date DATE,currency_code VARCHAR(3),cash_total"
            + " NUMERIC(19,6),office_id BIGINT,cashier_id BIGINT,status"
            + " VARCHAR(30),completed_on_utc TIMESTAMP,cashier_transaction_id BIGINT)");
    jdbc.execute(
        "CREATE TABLE m_cashier_reconciliation(id BIGINT PRIMARY KEY,business_date"
            + " DATE,currency_code VARCHAR(3),cash_total NUMERIC(19,6),office_id BIGINT,status"
            + " VARCHAR(30),cashier_id BIGINT,completed_on_utc TIMESTAMP)");
    jdbc.execute(
        "CREATE TABLE m_savings_account_transaction(id BIGINT PRIMARY KEY,transaction_date DATE)");
    jdbc.execute(
        "CREATE TABLE m_base_teller_deposit(id BIGINT PRIMARY KEY,cashier_id BIGINT,"
            + "currency_code VARCHAR(3),funding_type VARCHAR(20),status VARCHAR(30),"
            + "amount NUMERIC(19,6),created_on_utc TIMESTAMP,savings_transaction_id BIGINT)");
    jdbc.execute(
        "CREATE TABLE m_base_teller_savings_opening(id BIGINT PRIMARY KEY,cashier_id"
            + " BIGINT,currency_code VARCHAR(3),funding_type VARCHAR(20),status VARCHAR(30),amount"
            + " NUMERIC(19,6),created_on_utc TIMESTAMP,initial_deposit_transaction_id BIGINT)");
    jdbc.execute(
        "CREATE TABLE m_base_teller_deposit_check_detail(id BIGINT PRIMARY KEY,deposit_id BIGINT,"
            + "amount NUMERIC(19,6))");
    jdbc.execute(
        "CREATE TABLE m_base_teller_credit_payment(id BIGINT PRIMARY KEY,cashier_id BIGINT,"
            + "currency_code VARCHAR(3),amount NUMERIC(19,6))");
    jdbc.execute(
        "CREATE TABLE m_base_teller_credit_payment_check(id BIGINT PRIMARY KEY,"
            + "credit_payment_id BIGINT,status VARCHAR(30),accepted_on_utc TIMESTAMP,"
            + "returned_on_utc TIMESTAMP)");
    jdbc.execute(
        "CREATE TABLE m_cash_operation_check(id BIGINT PRIMARY KEY,operation_id BIGINT,"
            + "deposit_check_detail_id BIGINT,amount NUMERIC(19,6))");
    jdbc.execute(
        "CREATE TABLE m_end_of_day_closing_check(id BIGINT PRIMARY KEY,reconciliation_id"
            + " BIGINT,source_type VARCHAR(10),currency_code VARCHAR(3),amount NUMERIC(19,6))");
    jdbc.execute(
        "CREATE TABLE m_cashier_reconciliation_check(id BIGINT PRIMARY KEY,reconciliation_id"
            + " BIGINT,deposit_check_detail_id BIGINT,amount NUMERIC(19,6))");
    jdbc.execute(
        "CREATE TABLE m_base_teller_returned_check(id BIGINT PRIMARY KEY,cashier_id"
            + " BIGINT,currency_code VARCHAR(3),amount NUMERIC(19,6),returned_on_date DATE,status"
            + " VARCHAR(30),deposit_check_detail_id BIGINT)");
  }
}
