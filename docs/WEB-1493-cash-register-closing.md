# WEB-1493 opening validation for cash register closing

The old closing context mapped the absence of a reconciliation to `OPEN`. The closing command consequently allowed a never-opened drawer to be reconciled, including with zero cash.

Closing now requires a completed opening/provisioning allocation, with a matching native allocation transaction, in the current tenant database for the selected destination cashier, office, business date and currency. Both supported cashier allocation types qualify. The check does not depend on the allocation amount or remaining balance; the opening workflow itself permits positive allocations, and a subsequently exhausted drawer remains eligible. There is no carry-forward opening or supported allocation reversal API. Non-completed records cannot authorize closing.

The context adds a closing-specific `registerStatus` (`NOT_OPENED`, `OPEN`, `COMPLETED`) and nullable `closingId`. Shared `CashManagementStatus` values and existing endpoint/request contracts remain compatible. `status` alone is not opening eligibility. The matching web-app branch fails closed when this new evidence is missing.

Opening and closing use the custody mutex before cashier locks. Closing selects the opening/native records under shared row locks, rereads the cashier after locking, and checks opening eligibility again at the common posting boundary before reconciliation, settlement or accounting commands. End-of-Day Closing retains its register-wide semantics for all tenant currencies, including unused currencies, while still requiring a completed register opening. The existing separate-authorizer, permission, office hierarchy and fingerprint checks remain in place. Closing explicitly uses transaction-local read committed isolation so a waiter sees a completed matching replay after taking the lock; matching authorized historical replay remains available.

Unopened commands throw the structured domain rule `error.msg.base.teller.closing.not.opened`; no migration is needed. A second distinct closing is rejected. A matching idempotent request returns the existing authorized receipt.

The context's transaction-group callback also previously skipped its first row. It now uses `RowCallbackHandler` correctly, so allocations are included in expected balances. Regressions assert a funded expected balance and an opened drawer with zero remaining cash.

## Validation

On 26 November 2026, `./mvnw verify -Dit.test=CashManagementPlatformServiceIntegrationTest,EndOfDayClosingPlatformServiceIntegrationTest,CashAllocationLiquibaseIntegrationTest` passed 970 unit tests, 39 PostgreSQL integration tests and the coverage gates, and built the plugin JAR. Changed-file Spotless validation passed. Full Spotless reports unrelated upstream formatting violations, reproduced against unmodified upstream `7f31cd2`.

Integration tests exercise actual PostgreSQL transactions, row locks, migrations and uniqueness constraints. Native Fineract commands are mocked at the boundary and write settlement rows into the same database transaction. Cases include never-opened/zero-cash closing, zero remaining balance after opening, wrong scope/date/currency/cashier/office, incomplete/cancelled/reversed openings, missing native linkage, permissions, self-authorization, duplicate commands, two concurrent distinct commands, concurrent identical replay from a repeatable-read caller, historical replay and EOD rollback with no financial writes.

Reviewed the original [Savings Plugin #107](https://github.com/openMF/savings-plugin/pull/107) and [web-app #4050](https://github.com/openMF/web-app/pull/4050), including historical review feedback. The backend thread has no actionable review; frontend idempotency, stale-context and translation safeguards are addressed in the companion.

Deploy this plugin before [the companion frontend](https://github.com/openMF/web-app/compare/dev...AnvayKharb:fix/WEB-1493-cash-register-closing-validation?expand=1). Screenshots and frontend validation details are in its `docs/WEB-1493-cash-register-closing.md`. A safe live Fineract opening/closing/manual accounting exercise remains required; there was no running local Fineract service. This is implementation and automated regression evidence, not a claim of production deployment or ticket resolution.

## Full integration-suite follow-up

The first full CI run exposed shared-fixture failures beyond the focused closing suites. The reversal HTTP fixture had used a teller command's `resourceId` as its cashier ID, which could address another suite's drawer when the IDs differed. It now resolves the actual assignment by its newly created staff and teller and owns a separate office. It performs a real vault/head-cashier opening and then a real bank deposit to exhaust that opening, preserving the existing reversal tests' zero starting custody while satisfying opening validation. The financial activity setup uses an equity account for the native opening-balance contra requirement. No financial rows are fabricated to establish eligibility.

The native savings-policy test also asserted an obsolete dependency version. Failsafe now supplies the exact Maven `fineract.version`; the test requires the corresponding provider filename and classifier and still verifies the deployed provider and plugin hashes. Version and accounting assertions remain enforced.

Focused rechecks passed all 11 reversal HTTP tests and the native savings-policy HTTP test. The unfiltered Maven integration suite and the [PR checks](https://github.com/openMF/savings-plugin/pull/154/checks) provide the full-suite verification. No test exclusions, disabled tests, ignored failures or reduced coverage thresholds were introduced.
