# WEB-1256 Base Teller transaction history

## Source-of-truth matrix

The read model emits one history row for one operational teller movement. Plugin workflow tables
are authoritative where they exist. A native `m_cashier_transactions` row is emitted only when it
is not linked to a plugin workflow, which prevents accounting the same movement twice.

| History source | Authoritative record | Operation | Teller-relative effect | Status/date |
|---|---|---|---|---|
| `SAVINGS_DEPOSIT` | `m_base_teller_deposit` | `SAVINGS_DEPOSIT` | inflow | workflow status / completed timestamp |
| `SAVINGS_OPENING` | `m_base_teller_savings_opening` | `SAVINGS_OPENING` | inflow | workflow status / completed timestamp |
| `RETURNED_CHECK_PAYMENT` | `m_returned_check_payment` | `RETURNED_CHECK_SETTLEMENT` | inflow | workflow status / business date |
| `SERVICE_PAYMENT` | `m_service_payment` | `PAY_SERVICE` | inflow | workflow status / business date |
| `CREDIT_PAYMENT` | `m_base_teller_credit_payment` | `CREDIT_PAYMENT` | inflow | workflow status / business date |
| `CASH_ALLOCATION` | `m_cash_allocation` | allocation operation type | source outflow or destination inflow | workflow status / business date |
| `CASHIER_CLOSING` | `m_cashier_reconciliation` | `CASHIER_CLOSING` | outflow | workflow status / business date |
| `CASH_OPERATION` | `m_cash_operation_transaction` | cash operation type | outflow | workflow status / business date |
| `CASHIER_TRANSACTION` | unlinked `m_cashier_transactions` | native transaction type | types 101/103 inflow; 102/104 outflow | completed / transaction date |

The returned-check detection event, catalog changes, inventory snapshots, journal entries, loan
transactions, savings transactions, receipts, and denomination rows are not independent history
movements. They are status, accounting, or supporting evidence and are therefore not summed.

## Stable identifiers and amounts

History IDs are opaque canonical strings in the form `<SOURCE_TYPE>:<SOURCE_ID>`. Detail,
denomination, and receipt endpoints accept this same identifier. IDs are not derived from a list
position or a mutable receipt number.

`inflow` and `outflow` are teller-relative and non-negative. `total` is always
`totalInflows - totalOutflows`; totals are calculated over the complete filtered data set, not only
the requested page, and are kept separate by currency. Cash received and change are shown only
when the authoritative workflow persisted them. Reference filtering searches business-facing
references and descriptions, not idempotency keys.

## Denominations and receipts

Denominations are returned only from persisted denomination tables. An empty list with a false
support flag means the source did not persist that kind of breakdown; the API does not infer it.
The current workflows do not persist change denominations, so `changeDenominationsSupported` is
false. A denomination type is null when the source did not record one.

Receipt payloads delegate to the existing workflow receipt/read service. Unsupported native or
cash-operation records explicitly return `receiptSupported=false`. The report endpoint returns the
full filtered, structured data set for Web App printing and does not claim to produce a PDF.

## Authorization and office scope

Every route and service entry point requires `READ_BASE_TELLER_TRANSACTION_HISTORY`. Office scope
is derived from the authenticated user's office hierarchy. A user with an active cashier
assignment is narrowed to that assignment unless the user also has the existing
`READ_GLOBAL_SETTLEMENT` authority. Client-provided office or teller IDs can only narrow the
server-computed scope; they cannot widen it.
