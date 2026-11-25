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

## Subscription Android integration (unverified)

Settings → Μετρητά includes manual subscription entry, review of equal bank debits in different months of the current three-calendar-month window, editing/removal, and estimated monthly total. Rent and loan repayments are excluded. A user must enter the actual monthly equivalent; matching two amounts does not establish payment frequency. A confirmed record is keyed by normalized name, stored in the encrypted database and included in logical backup. No new financial movement is created. Dismissal is session-only. Receipt-derived suggestions remain pending reconciliation. The new table extends unreleased schema 8 (no verified APK/schema 8 export exists); experimental earlier schema-8 installations require migration review before use. Android build, migration and backup tests remain unverified.

Verification: on JDK 21, standalone kotlinc compilation of the fetched subscription logic and BankMovement model with a synthetic harness passed 13 checks (matching amounts and payees across distinct months, case normalization, date bounds, excluded rent/loan/transfer, overflow guard and invalid manual amount). A host SQLite smoke check passed 4 assertions for recent debit selection, unique-name update, removal and untouched bank movements. These are not Gradle/JUnit, Android Room, SQLCipher, migration or backup verification. No APK claimed.

## Monthly dashboard integration (unverified Android build)

The existing finance screen now starts with a month selector and a consolidated bank/cash overview. A single Room UNION ALL projection reads the selected period up to today; no new schema is needed. Gross expenses, refunds, net expenses, income by source, actual rent and loan payments and net flow are displayed. Transfers marked as such are excluded, subscriptions remain separate forecasts, and receipts are not added pending reconciliation. Manual cash entries must represent real income or expenses, not transfers; automatic cash/bank matching and opening balances are not implemented. Added domain regression tests. Android compilation and full Gradle tests remain pending.

Verification: compiled the exact MonthlyOverview.kt and BankLedger.kt blobs (Git SHA-1 matched the remote files) with standalone kotlinc on JDK 21. Synthetic harness passed 16/16 checks for source totals, transfers, month boundaries, refunds, obligations, empty/negative months and overflow. Executed the dashboard UNION ALL query on synthetic SQLite tables: 4/4 checks passed for date bounds, account selection, cash sign classification and total. These checks do not verify Compose, Room code generation, SQLCipher, the full Gradle suite or an APK.

## Monthly planning (unverified Android build)

Added a separate per-calendar-month finance plan (spending limit, expected total income, savings target), stored in the encrypted database and logical backups without changing the existing receipt budgets. Progress compares net recorded expenses with the limit, warns at 80%, at the limit and above it, and compares recorded net flow with the savings goal. A simple month-end pace estimate is shown after day 7 with at least one recorded movement; it uses planned total income rather than extrapolating salary. It is not a prediction of individual bills or next payday. No second transaction is generated. Added domain tests; Gradle/Android compilation, Room migration and backup tests still pending. The table extends unreleased schema 8; earlier experimental installations need migration review.

Verification: standalone kotlinc/JDK 21 compilation of MonthlyPlan.kt, MonthlyOverview.kt and BankLedger.kt (all three Git blob hashes matched the branch) passed 15/15 synthetic assertions for spending thresholds, refunds, projections, insufficient data, completed months, savings goals and invalid input. No Android or Room verification claimed.

## Full-resolution receipt photo capture (unverified Android build)

The existing QR scanner can already pick a gallery photo and decode its QR. Added a separate camera capture button using ActivityResultContracts.TakePicture and a narrowly scoped, non-exported FileProvider. The full-resolution image is temporarily held in private cache, decoded by the existing on-device QR reader, then deleted after processing or cancellation. Gallery photos are not deleted. The existing provider link import is preserved. This does not recognize printed text, retain receipt images or import receipts without usable QR codes. Those require on-device Greek/English OCR and reviewed manual receipt persistence; no APK/build test yet.

## Greek/English OCR preview (unverified Android build)

Added Tesseract4Android 4.9.0, scoped JitPack dependency, build-time language assets from tessdata_fast release 4.1.0 commit 65727574dfcd264acbb0c3e07860e4e9e9b22185 with license, and local OCR fallback for gallery/camera photos without a readable QR. Images are bounded to 4096 pixels per side, processed on a background dispatcher, and temporary camera photos are removed after processing. Raw editable preview is in memory only; no receipt or financial movement is written. No external OCR API is called. Model downloads and Android dependency resolution are not verified here; EXIF rotation, actual receipt field extraction, native ABI/16 KiB packaging checks and persistent reviewed receipt import remain pending.

Verification: a host Tesseract CLI smoke test using installed ell+eng data recognized Greek product lines and the total 3,70 from a synthetic clean image; the English TOTAL line was misread as Greek characters. This is not an Android integration test or a test of the exact pinned model files. The local host cannot resolve raw.githubusercontent.com, so those assets and the native Android library remain unverified.

## Structured OCR receipt preview (unverified Android build)

Added a conservative Greek/English text parser for single-line items, explicit quantity × unit price, explicit discounts/coupons, printed VAT and payable total. Money uses integer cents. Payment lines are excluded; VAT is not added again. Conflicting totals, arithmetic mismatches and ambiguous tax values require review. Editing OCR text refreshes the structured preview. This remains an in-memory preview, not a saved receipt or financial movement. Merchant/date extraction, complex multiline layouts, discount allocation, photo persistence and bank reconciliation remain pending. Added domain tests; Android compilation is not verified.

Verification: standalone kotlinc/JDK 21 compiled the exact parser blob (Git SHA-1 matched the branch); 15/15 synthetic assertions passed for Greek items, quantities, discounts, VAT, totals, payment lines and inconsistent values. The Android UI, native OCR, Room and APK remain unverified.

## Reviewed OCR receipt persistence (unverified Android build)

A reviewed sale in EUR can now be submitted from the OCR dialog to the existing Persister and WriteCoordinator. The user must enter merchant name, real Greek VAT number with checksum, date, series/receipt number, optional printed MARK, and confirm the text and total. The same receipt tables, encrypted backups and ProductMatcher are reused. Unknown quantities/units are not invented; standalone discounts are retained as negative lines without allocating them to products. Exact OCR source key or MARK opens an existing compatible receipt; same merchant/date/amount triggers a duplicate warning. A receipt saved without a source link is not falsely labeled as imported from another phone. Raw OCR and photos are not persisted. No bank or cash movement is created until a separate reconciliation step is implemented. Distinct stores of one VAT issuer are not identified yet; actual cross-store comparison needs verified product matching and unit information. Android compilation, integration, sync and backup tests are not verified.

Added four JUnit tests for canonical mapping, stable identity, invalid VAT and inconsistent totals. Tests are written but not run in the Android/Gradle suite.

## Price observatory (unverified Android build)

Extended existing product price history rather than creating another catalog. Shows observed first-to-latest percentage movement and latest purchase price/date per merchant for the selected normalized unit and currency (kg/l/piece). Same-time conflicting observations are flagged, not ranked. Merchant receipt counts are distinct. Existing weighted-average comparison and historical charts remain intact. These are observed historical purchase prices, not live shelf offers or a general inflation index. Receipt-level discounts remain unallocated. Added two regression tests; not run in Android/Gradle. No schema changes.

## Receipt-bank reconciliation (unverified Android build)

Added one-to-one links between existing receipts and imported bank debits. Candidates require an active, included EUR sale with explicit card/bank payment, exact positive total, expense debit not categorized rent/loan, and posting date from receipt date through three days later. User reviews each candidate; no automatic linking. Database uniqueness and foreign keys prevent reuse and cascade on deletion; write rechecks eligibility. Incompatible bank classification requires unlinking first. A link can be removed without deleting either record. Backups list the new table after both parents. The monthly bank/cash dashboard still does not add receipt-only spending: linked purchases count through the existing bank debit only, and unlinked/cash receipts are not yet integrated. Tax eligibility remains separate and pending. No split payments, partial matching, cash receipt matching, or automatic cross-device link sync. New table extends unreleased schema 8; experimental installs require migration review. Android build, Room, migration and backup verification pending.

Verification: ran the confirmation INSERT SELECT SQL against synthetic SQLite tables with foreign keys enabled. 14/14 assertions passed for amount/date boundaries, excluded rent/loan/transfer, cash and cancelled receipts, one-to-one uniqueness, unlink preservation and receipt deletion cascade. This is a host SQL smoke test, not a Room/SQLCipher integration or Android build test.

## Reviewed annual Greek electronic-spending estimate (unverified Android build)

Added annual per-person plan and bank movement review (pending/excluded/eligible/special health ×2). Target 30% or explicitly selected lawful 20%/exemption, cap EUR 20,000 or confirmed special EUR 5,000 cap, shortfall ×22%, only years 2022–2027. Sources: Greek Income Tax Code article 15(6), article 40(4), A.1163/2020, decision 35565 EX 2022 (specified health services only), Law 5355/2026 FEK A 188/13-11-2026 (extension through 2027). No automatic entitlement inference or healthcare doubling. Linked receipt merchant displayed as supporting evidence; confirmed bank row is counted once, not summed with receipts. Refunds require review and subtract. Rent/loan/transfers/income excluded. Old receipt-only card retained with non-additive warning. Bank posting date used; year-end timing, incomplete statements, multiple taxpayers, automatic MCC rules, receipt-only payments and separate article 15(7) deduction still need review. Added two tables to unreleased schema 8, included in logical backup; review resets when bank kind/category changes. Android build/migration/backup not verified.

Verification: standalone kotlinc/JDK 21 compiled TaxSpending.kt and BankLedger.kt; both Git blob hashes matched the branch. All 16 synthetic assertions passed for target rates, caps, refunds, eligible/doubled payments, pending reviews, excluded categories, year boundaries, duplicates and invalid plans. Added equivalent JUnit regression tests (not executed in Gradle). Room, Compose, migrations and APK unverified.

## Lightyear-first investment tracking (unverified Android build)

Official sources checked 2026-11-26: https://lightyear.com/en-eu/help/getting-verified/do-i-have-to-download-the-app-or-do-you-have-a-website (no official API); https://lightyear.com/en-ee/blog/spring-product-update (income/capital gains CSV); https://lightyear.com/en-es/blog/lightyear-autodeclaro (account statement CSV). Added manual dated EUR portfolio snapshots (total including broker cash, cumulative lifetime deposits and withdrawals), arithmetic gain vs net funding, funding-adjusted change between observations, and separate dated holdings with quantity, value and optional open-position cost. Same broker/day or broker/instrument/day replaces observation; cumulative funding must be monotonic. No transaction or tax spending generated, no double count of holdings, no credentials, no live quotes. Assumes no unrecorded external securities transfers. Not a time-weighted return or tax basis calculation. Broker-specific CSV mapping requires a verified export schema; not yet implemented. Schema 8 remains unreleased; new tables in logical backup. Android/Room/migration/build pending.

Verification: exact InvestmentTracking.kt Git blob compiled with standalone kotlinc/JDK 21; 15/15 checks passed for deposits, withdrawals, losses, cumulative funding, chronology, invalid negatives and extreme values. Added JUnit tests (Gradle not run). Android, Room, SQLCipher and APK remain unverified.

## Optional public price tracking for Lightyear (unverified Android build)

Added Twelve Data time_series daily split-adjusted prices for explicitly entered symbol + exchange MIC, using a user-supplied API key held only in Compose memory. HTTPS requests to api.twelvedata.com (no redirects, bounded response, timeouts), validate returned symbol/MIC, currency and dates, no holdings/quantity/portfolio values transmitted. Opt-in refresh on start, manual refresh or every six hours while the composable remains present; no WorkManager background polling or persistent quotes. Display latest observation date and fetch time, stale/error indicator, daily/weekly/monthly/yearly price changes using dated baselines within seven days of cutoff. No FX, total return, dividend, automatic holdings valuation, alerts or CSV integration. Do not imply live or final closing data; today's bar may be provisional. European exchange coverage may require paid access. Official docs https://twelvedata.com/docs and https://twelvedata.com/pricing, checked 2026-11-26. API/network and Android integration not verified; no personal API key requested or stored in GitHub.

Verification: standalone kotlinc/JDK 21 compiled exact MarketPriceHistory.kt (Git blob hash matched); 13/13 synthetic checks passed. Added JUnit regression tests, not executed by Gradle. Provider requests, Android UI, lifecycle behavior and APK remain unverified.

## Manual Lightyear file import (unverified Android build)

User prefers uploading files manually instead of requiring an API. Manual import is now the primary path; existing optional market API panel is collapsed. Local UTF-8 CSV snapshot import supports explicitly defined EUR portfolio or holdings headers, strict dates, cumulative flows, preview with pagination and atomic commit. Identical snapshots skipped; conflicting keys cancel batch, no automatic overwrite. No schema change. Native Lightyear statement header/transaction semantics remain unverified; this is a snapshot interchange format, not a native statement parser. No external upload, automatic FX, dividends, trade cost basis or background quote polling. Android build/Room execution pending.

Added JUnit regression cases for sorted snapshots, funding-adjusted result, fractional holdings, missing cost, foreign currency, duplicate dates, future dates and decreasing cumulative deposits. Tests written, not executed here.

## Simple Lightyear progress assessment

Descriptive lifetime and latest-snapshot directions (up/down/flat), actual date range, EUR gain change net of funding, percent only if starting value is positive and no external deposits or withdrawals occurred. First observation has no trend. No trading advice, benchmark judgment or risk inference. Manual upload remains primary, optional API remains collapsed. No schema changes. JUnit cases added; Android build not run.

Verification: exact PortfolioDirection.kt and InvestmentTracking.kt Git blobs compiled with standalone kotlinc/JDK 21; 9/9 checks passed for total gain versus recent decline, deposit-neutral result, absent percent when funding changes, and first-snapshot behavior. Android UI and CSV import remain unverified.

## Lightyear file-based valuation, FX, dividends and import notifications

Added two explicit CSV interchange formats: complete dated positions/cash with quantity × price × EUR-per-currency rate, cumulative funding in EUR, and dividend events with stable references, gross, withholding, fees and rate. Valuation sums rounded EUR cents; requires explicit cash and user confirmation of complete coverage; same-day rates per currency must agree. Portfolio and holdings commit in one transaction; original foreign valuation rows are not stored, only resulting EUR snapshots. Dividends retain original amounts and rate, are summarized by year and never added a second time to portfolio gain. Optional Android notification after successful new import (permission requested, generic content, no background monitoring). Native statement mapping, automatic FX retrieval, market movement alerts while closed, and Android/Room/backup/migration verification remain pending. Dividend table extends unreleased schema 8.

Verification: standalone kotlinc compiled exact LightyearValuation.kt and InvestmentTracking.kt (both Git blob hashes matched); 15/15 synthetic checks passed for FX, rounding, totals, overflow and dividend deductions. Added JUnit regression tests including CSV parsing, not executed with Gradle. Valuation import reports both portfolio and holding records and previews both. Android notifications, permissions, CSV UI, Room transaction and APK remain unverified.

## Net worth (unverified Android build)

Added explicitly confirmed dated EUR balance sheets with positive bank balances, cash outside broker, Lightyear including broker cash, other assets, outstanding loan principal and other debts (including overdrafts). Net worth = assets minus liabilities; liquid outside broker separate. Exact same-day Lightyear valuation automatically prefilled when selected; missing data never converted to zero or carried forward. Manual alternative and explicit zero permitted. User confirms completeness and same-day replacement; transactional stale-write and source-value checks. Historical snapshots freeze confirmed values, and the latest warns if the referenced source changes. Latest/previous change in EUR and dated history, not investment return or inferred bank balance. No ledger/tax movement created. Added table to unreleased schema 8 and logical backup; Android/Room/migration/backup/APK remain unverified.

## Payday calendar (unverified Android build)

Added explicit current-day liquid balance and next payday, dated one-off rent/loan/subscription/other obligations, pending/paid/cancelled states, reserve through day before payday including overdue payments, shortfall and daily allowance. Current-day balance must be re-confirmed after a payment is marked paid; past-day balances never forecast automatically. No new ledger movements, no automatic recurring schedules or matching of subscriptions, no assumption of salary amount or payday timing. New tables included in logical backup and unreleased schema 8; Android, Room, migration, backup and APK unverified. Added JUnit regression cases.

Verification: standalone kotlinc/JDK 21 compiled exact PaydayCalendar.kt, NetWorth.kt and InvestmentTracking.kt (Git blob hashes matched). 12/12 payday and 11/11 net-worth assertions passed, including overdue obligations, paid/cancelled exclusions, payday boundary, daily rounding, stale balance, negative net worth, principal repayment neutrality and exact-date Lightyear lookup. Added NetWorth regression tests; Gradle/Room/Android not run.

## Local financial advisor

Added rule-based observations from the existing bank/cash ledger, current monthly plan, subscription estimates, last two confirmed net-worth snapshots and current payday forecast. Ranks gross categories separately from refunds, highlights unclassified amounts, budget thresholds, recorded cash-flow deficit and reserve shortfall. Equal elapsed-day comparison and forecast/savings observations require explicit session confirmation of data coverage; no fabricated trend when a comparison period lacks movements. No new financial movements, no network, no new database schema or AI inference. Receipts not independently counted. Android build and integration pending.

Verification: standalone kotlinc compiled the exact FinanceAdvisor.kt Git blob with existing finance engine sources. 14/14 synthetic assertions passed for recorded totals, refund and transfer treatment, equal-day comparison, coverage gating, budget, savings, subscriptions, net worth and payday deficit. Added JUnit regression tests; Gradle and Android integration remain unverified.

## Stabilization pass 1

Read actual failed GitHub CI run 45722811727, job 139585174531: Gradle 9.9.1 and JDK 25 succeeded; feature/scan/build.gradle.kts failed on java.net.URI because the Kotlin DSL java extension shadows the package. Imported URI explicitly. Also corrected cross-module nullable quantity access in reviewed OCR. Release run 45722811766 failed because version 0.3.6 equaled master; bumped candidate to 0.4.0 (code 12) with honest release notes. App label MyEksoda; application ID unchanged to avoid accidental data isolation; final distinct identity and icon still pending. Home gets a direct finance entry, finance screen gets fixed horizontal jump navigation to existing panels. Existing dark theme preserved. No gates disabled. These fixes require fresh CI confirmation; no APK or end-to-end verification claimed.

## Stabilization pass 2

Release check run 45723717408 succeeded. CI run 45723717412 failed during configuration: AGP rejects Provider in legacy Android SourceSet API. Replaced legacy assets registration and preBuild hook with a typed Gradle task (input revision, output directory) registered per variant through androidComponents and assets.addGeneratedSourceDirectory, following https://developer.android.com/build/extend-agp. No compatibility escape hatch or quality gate disabled. Fresh CI confirmation required; no verified APK yet.

## Stabilization pass 3

Release run 45724114509 succeeded; CI run 45724114520 (job 139589487685) failed in the OCR Gradle script on java.nio.file references shadowed by the Java plugin extension. Explicit imports for Files and StandardCopyOption remove the two reported errors; checked the entire scan Gradle script for remaining qualified java package expressions outside imports. New CI result required. No APK yet, no quality checks bypassed.
