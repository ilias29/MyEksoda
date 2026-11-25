# Finance release gates

Owner authorizes routine implementation and testing without per-version approval. Keep work on feature branches; do not merge failing or unverified builds. No real financial data in public repository.

- v0.1: bank CSV/XLSX, accounts, encrypted persistence, import preview, income/expense/refund/transfer classification and duplicate handling.
- v0.2: Greek/English receipt OCR, discounts, validation and correction.
- v0.3: receipt/bank reconciliation without double counting.
- v0.4: investment imports, holdings, fees, dividends and valuation.
- v0.5: unified reports, budgets, forecasts and goals.
- v1.0: regression, migration, privacy and security checks; distinct branding/application ID; installable APK.

For each version: implement, review, run unit and regression tests, fix failures, build debug APK, record results and known limitations, and only then mark the version complete. Never claim tests passed or APK exists without evidence. Escalate only material blockers, missing sample formats, credentials, or destructive changes. Existing receipt features must remain functional.
