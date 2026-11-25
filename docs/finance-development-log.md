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
