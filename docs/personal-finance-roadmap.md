# Personal Finance & Wealth Tracker

Started 2026-11-25. Based on Eksoda, retain GPL notices and use distinct branding, icon and application ID before distribution.

## Scope
Preserve existing QR receipt scanning, products, price history, budgets and tax allowance. Add local CSV/XLSX imports with configurable column mapping, accounts, income, expenses, refunds and transfers; preview and validate imports, preserve source identifiers and avoid repeated imports. Match receipts to bank payments without double counting; ambiguous matches require confirmation. Add cash/manual transactions and category overrides.

Add on-device Greek/English receipt OCR, line items, quantities, original/net prices, discounts, coupons, VAT and total validation. Do not infer discounts absent from the source. Avoid counting discounts twice. Preserve QR workflow.

Add investment account imports, trades, dividends, fees, currencies, holdings, cost basis, valuation and net worth (assets minus liabilities). Internal transfers and purchases of investments are not consumption expenses. Optional market data retrieval must not transmit holdings or transactions.

## Implementation sequence
1. Audit existing schema, migrations, receipt and security workflows; establish regression tests.
2. Introduce ledger and account models using exact monetary arithmetic, source provenance and reversible imports.
3. CSV/XLSX import and reconciliation, then OCR and discount extraction.
4. Investment import and valuation, unified reports, budgets and goals.
5. Distinct branding and application ID, security checks, migration tests, Android build and APK.

## Acceptance tests
Repeated imports are idempotent; ambiguous identical payments are not silently merged; transfers net to zero in spending reports; linked receipts count once; refunds reduce relevant expenses; discounts reconcile with payable totals; investment funding does not count as spending; existing QR and budget workflows remain functional. No credentials, analytics or remote financial-data uploads. Use anonymized sample statements to validate each bank/broker format.

Status: planning only; no implementation or build yet.
