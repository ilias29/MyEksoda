# Personal finance development log

## 2026-11-25 — v0.1 in progress

- Extended the existing EUR bank CSV parser with row preview and validation that blocks commit on malformed rows.
- Added source-ID reconciliation scoped per account: same bank ID and same payment is skipped; same ID with different payment is a conflict. Payments without IDs remain separate for review.
- Added editable rent and loan repayment category suggestions. Loan disbursement is not a loan repayment. Principal versus interest cannot be inferred from a single undivided debit.
- Added domain unit tests for validation, classification, manual category correction and deduplication.
- Added a manual cash entry model: positive income and negative expenses are included in personal cash flow. Added separate manual subscription records and suggestions from at least two equal payments to the same payee in different months within a rolling three-month window (for example Viber); suggestions ask for user confirmation and create no duplicate expense.
- Added CI artifact upload for a debug APK, conditional on a successful `make check`.

Local verification: `git diff --check` passed. `./gradlew :core:engine:test --offline` did not start tests: Gradle 9.9.1 is not cached and this host cannot reach services.gradle.org. No test success or APK claimed.

Release gate: v0.1 **open**. CSV and manual cash are not connected to the Android UI or the encrypted database; XLS/XLSX parsing, account management and full import reversal remain to be built. Subscription detection is a domain prototype, not an active feature. Bank-specific formats need anonymized examples before their adapters can be validated. Keep PR draft.

## Manual cash Android integration
Settings → Μετρητά now opens a form for dated EUR income, expenses, rent and loan repayments. The form writes to a new Room table in the existing SQLCipher database. It displays the recorded movements and the sum of positive cash entries. Schema version 8 declares an additive 7→8 Room migration. Bank CSV and subscription prompts still need UI integration.

Executed a SQLite smoke check with the exported version 7 table definitions and two synthetic cash movements. Original table definitions were preserved and the positive income total was correct. This check covers raw SQLite only. Android Gradle tests and assembleDebug stopped before compilation because the wrapper could not download Gradle. Generated Room schema, migration verification on Android, full regressions and APK remain pending.

## Bank CSV Android integration
Settings → Μετρητά now includes a local document picker, account name, column positions, date pattern, decimal separator and delimiter. The input is a UTF-8 CSV with a header, signed EUR amount and a stable reference in each row. The screen previews up to ten rows and explicitly confirms the full count. Room writes the batch atomically in the SQLCipher database. The account/reference key makes re-import idempotent and conflicting source details cancel the batch. The table extends the unreleased schema 8; upgrading an experimental schema-8 installation from an earlier development commit has not been validated.

A host SQLite experiment exercised duplicate keys, separate accounts and rollback on a conflicting reference. It did not execute Room or the Android UI. Gradle unit tests and assembleDebug could not start because the Gradle distribution was unreachable. XLS/XLSX and exports without stable IDs are still unsupported by this screen.
