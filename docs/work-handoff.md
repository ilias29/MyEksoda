# MyEksoda — Work handoff

Repository: https://github.com/ilias29/MyEksoda
Branch: feature/personal-finance
Draft PR: https://github.com/ilias29/MyEksoda/pull/1

Read personal-finance-roadmap.md and finance-release-gates.md before implementation. Continue existing code, not a rewrite. Current state: BankLedger and BankCsv domain code with preview, conservative rent/loan payment suggestions and ID-based import reconciliation. These changes are not integrated with UI or encrypted storage. Unit tests and APK are not verified yet.

## Deliverables
- v0.1: Greek bank CSV/XLS/XLSX imports, configurable mappings and preview, multiple accounts, income/expenses/refunds/transfers, editable automatic categories, duplicate prevention, manual cash, encrypted storage.
- v0.2: Greek/English receipt photo OCR, store/date/items/quantities/unit and original prices/VAT/total, discounts/coupons, savings statistics, manual correction and validation; preserve QR.
- v0.3: reconcile bank payments and receipts without double counting; unified categories, stores, products, price history and tax allowance.
- v0.4: Trading 212, Revolut, Freedom24, DEGIRO, Interactive Brokers and other broker imports where formats permit; stocks, ETFs, dividends, fees, currencies, cost basis, realized/unrealized gains, valuation and net worth. Investment funding is not consumption.
- v0.5: dashboard, monthly/yearly reports, spending breakdown, forecasts, recurring payments/subscriptions, savings goals, overspending alerts, product/store price comparisons, report exports.
- v1.0: Greek UI, dark mode, distinct branding/icon/application ID, GPL compliance and attribution, privacy/security/migration/regression checks, working APK and documentation.

## Execution
Owner authorizes routine development and testing without repeated approval. For every version implement, review, run tests, fix failures, verify previous functionality, build debug APK, document evidence and commit. Advance only after gates pass. Keep unverified changes on development branch. Ask owner only for material blockers, permissions, anonymized sample formats or destructive changes. Never upload private financial data to public GitHub, request bank credentials, or transmit private financial data to external services. Prefer local OCR; optional market data must not expose holdings. Preserve existing functionality. Do not claim tests or APK without evidence. Final deliverables: source, documentation, test results and installable APK. Work environment/build availability is not yet confirmed.
