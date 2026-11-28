# CI repair log

## Baseline: 8498463 (2026-11-26)

- Full clone, 162 commits; reviewed the 57 PR commits and previous stabilization notes.
- CI run 45866495743, job 140070550435: `make check` fails at Spotless.
  Failure diagnostics identify long lines in app, database, engine tests and scan;
  formatter cannot converge on BankHistory. Diagnostic `assembleDebug` succeeds,
  but tests, detekt, lint, coverage and verified APK upload have not passed.
- Previous fixes addressed Kotlin DSL Java package shadowing, generated OCR assets,
  explicit Kotlin APIs and wildcard imports. Do not redo those without new errors.

## Execution attempts

1. Plain wrapper: distribution download fails with `Network is unreachable`.
   Explicit JVM proxy flags alone did not fix the wrapper download. Do not retry
   this route unchanged.
2. Downloaded the exact Gradle 9.9.1 distribution with proxy-aware curl and seeded
   the wrapper cache. `./gradlew --version` succeeds. No wrapper downgrade.
3. Gradle toolchain download initially used a stale proxy port (connection refused).
   Environment proxy ports change between command invocations; generate local
   user Gradle proxy properties from the current environment before each run.
   JBR 25 and the Android SDK were subsequently downloaded successfully.
4. First format run cannot resolve the kotlin-dsl plugin. Investigating with
   a fresh single-use daemon and verbose dependency diagnostics; curl can fetch
   the exact plugin marker POM. Do not misdiagnose this as an invalid version.

No commit, merge, feature addition or quality-gate change performed in this pass yet.

## Formatting repair and pre-commit checks

- JBR's separate certificate store prevented plugin resolution; using the host
  Java trust store allowed dependency downloads. No repository trust or TLS
  verification settings were changed.
- The build environment then rejected Maven Central access explicitly:
  `Network access to "https://repo.maven.apache.org:443" was blocked by policy`.
  Full local Gradle verification cannot proceed here; use unchanged GitHub CI.
- Ran the project's exact ktlint 1.8.0 CLI with the repository `.editorconfig`
  and module source/build-script targets. Automatic formatting corrected 67 files;
  22 remaining long-line findings required explicit wrapping, braced conditions,
  or equivalent local expressions. No rule suppression was added.
- A scripted argument wrapping edit accidentally omitted commas in DividendRow.
  The parser check caught it immediately; restored separators and reran formatting.
- Final standalone ktlint check: PASS (zero findings). `git diff --check`: PASS.
  This is formatter verification, not a claim that Gradle, tests, coverage or APK pass.
- Next: push one formatting repair to the existing branch and inspect all CI gates.
