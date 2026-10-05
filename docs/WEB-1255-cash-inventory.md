# WEB-1255 cash inventory read model

## Operational period

The endpoint uses the current Fineract business date, matching the WEB-1221 allocation and
WEB-1232 closing workflows. Teller cash opening is native cashier transaction type `101` on that
date. Types `103` and `104` are accumulated cash inflow and outflow. Type `102` is classified by
its originating record: a cash-allocation source or cash-operation settlement is an outflow;
otherwise it is a cutoff. Closing cash received by the vault is sourced from the completed
reconciliation record. Values reset with the next business day's opening workflow.

Checks are physical custody instruments. Checks accepted before the business date and not removed
before it form initial check inventory. Today's acceptances are inflows. Cash operations and
returned checks are outflows; reconciliation selections are cutoffs. This allows check custody to
carry between business days without treating a cleared check as cash.

`showLastCutOff` controls whether `lastCutOffAt` and `lastCutOffAmount` are populated. It never
changes the arithmetic: every completed cutoff is always included in `cutOffs` and deducted from
`balance`.

## Source-of-truth matrix

| Operation | Authoritative inventory source | Type/effect | Custodian | Already in `m_cashier_transactions` |
|---|---|---|---|---|
| Safe/vault opening | `m_cash_allocation` completed `SAFE_VAULT_OPENING` | cash initial | vault | No |
| Vault to head cashier | `m_cash_allocation` for vault; native type 101 for cashier | cash vault outflow / teller initial | vault, teller | Teller side only |
| Head to operational teller | native linked type 102 and 101 | cash source outflow / destination initial | tellers | Yes |
| Cashier closing | native type 102 for teller; `m_cashier_reconciliation.cash_total` for vault | cash cutoff / vault inflow | teller, vault | Teller side only |
| Cash operation | native linked type 102 | cash outflow | teller | Yes |
| Deposit in transit | `m_cash_operation_transaction` plus its selected checks | cash/check transit inflow | transit | Teller cash side only |
| Savings cash deposit | completed plugin deposit plus native savings transaction date | cash inflow | teller | No |
| Savings-opening cash funding | completed plugin opening plus native savings transaction date | cash inflow | teller | No |
| Service payment | native type 103 | cash inflow | teller | Yes |
| Cash credit payment | native type 103 | cash inflow | teller | Yes |
| Returned-check cash settlement | native type 103 | cash inflow | teller | Yes |
| Savings check receipt | deposit and check detail | check initial/inflow | teller | No |
| Savings-opening check | completed opening record | check initial/inflow | teller | No |
| Credit-payment check | credit check accepted timestamp and state | check initial/inflow | teller | No |
| Returned/rejected check | returned-check record or credit-check `RETURNED` state | check outflow | teller | No |
| Check sent by cash operation | `m_cash_operation_check` | check outflow | teller | No |
| Check included in closing | `m_cashier_reconciliation_check` | teller cutoff / vault inflow | teller, vault | No |

Journal entries, receipts, denomination details, loan transactions, and savings transactions are
not independently summed. They provide accounting, evidence, or the authoritative business date,
but summing them again would duplicate the inventory effect.

## Custodians and authorization

Keys are typed and stable: `TELLER:<cashierId>`, `VAULT:<officeId>`, and
`TRANSIT:<officeId>`. Vault and transit are not synthetic users. A user with an active cashier
assignment is limited to that assignment unless they also hold `READ_GLOBAL_SETTLEMENT`, the
existing branch-wide reconciliation authority. Managers are still limited by their authenticated
office hierarchy. The requested key must be present in that server-computed scope.

## Known source limitation

The current cash-operation model records entry into transit but has no separate transit-to-bank
completion relation. Consequently completed `DEPOSIT_IN_TRANSIT` records remain in transit custody
until the operational model gains an authoritative completion link. The inventory API does not
invent such a movement.
