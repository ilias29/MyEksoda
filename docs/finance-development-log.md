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

## XLSX bank imports
The Android picker detects an XLSX archive and passes its rows to the same bank preview and commit flow as CSV. This reader accepts a single sheet with literal values and a header. Amounts, numeric Excel dates (1900 and 1904 systems), shared or inline text and sparse columns are supported. Text bank references preserve leading zeros. Number-format masks are not applied, so references with meaningful leading zeros must be text. Legacy binary XLS, multiple sheets and formula cells remain unsupported.

Executed python3 scripts/finance/check_xlsx.py on JDK 21 with synthetic openpyxl files. Both Excel date systems returned the expected date, negative amount, Greek description and text reference. The multiple-sheet sample was rejected. The Android Gradle build and unit test suite could not start because the wrapper could not fetch its distribution. No APK verified.

## Bank corrections and backups
Imported account names are selectable. Bank history now pages beyond ten records and allows sign-compatible corrections (including refunds and internal transfers). Account net spending subtracts refunds and excludes transfers. Re-import leaves the stored classification intact. The correction query checks the previous classification before writing. Both finance tables are included in the existing encrypted logical backup.

Executed the DAO update SQL against synthetic SQLite records: account isolation and stale-edit rejection passed. Re-ran the production XLSX reader fixture checks successfully. Added a Room regression test for re-import. Gradle could not fetch its distribution, so the Room test, backup round trip on Android, full build and APK remain unverified.

## Legacy Excel
The picker now recognizes OLE compound files and routes single-sheet BIFF workbooks through Apache POI HSSF, followed by the same preview and atomic import as CSV and XLSX. Added synthetic workbook tests for Greek descriptions, a formatted reference, a numeric date and amount, and rejection of formulas. The Android branch is included in the existing push CI trigger so a build can run when repository Actions are available.

Executed the existing XLSX fixture regression on JDK 21. The new XLS tests and debug build did not execute: the local wrapper could not fetch Gradle 9.9.1. The HSSF dependency and its Android runtime compatibility remain unverified. No APK produced.
