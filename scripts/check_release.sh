#!/usr/bin/env bash
# Checks that a pull request's branch is a release (`make release_check [BASE=origin/master]`).
# Every merge to master is a release, so a pull request must carry its own `make bump`:
#
# 1. version.properties: versionName newer and versionCode higher than on BASE (the branch it
#    merges into).
# 2. CHANGELOG.md: a `## <version>` section with release notes, without the TODO `make bump`
#    puts there (the same rules `make publish` applies).
#
# CI runs it on every pull request (.github/workflows/release.yml, check "release ready"), so a
# pull request without its bump cannot be merged once that check is required; one labeled
# "no release" (CI, scripts or docs only) skips it. Run it locally before pushing. Nothing is
# changed.
set -euo pipefail
cd "$(dirname "$0")/.."

BASE="${1:-origin/master}"
VERSION_FILE="version.properties"
CHANGELOG="CHANGELOG.md"
BUMP_HINT="run 'make bump BUMP=patch|minor|major' on this branch, write the notes, and commit both"

fail() {
    echo "release_check: $*" >&2
    exit 1
}

property() { sed -n "s/^$1=//p" | tail -n 1; }

git rev-parse --quiet --verify "$BASE^{commit}" >/dev/null ||
    fail "no '$BASE' to compare with (git fetch origin master)"

CODE="$(property versionCode <"$VERSION_FILE")"
NAME="$(property versionName <"$VERSION_FILE")"
BASE_CODE="$(git show "$BASE:$VERSION_FILE" | property versionCode)"
BASE_NAME="$(git show "$BASE:$VERSION_FILE" | property versionName)"
[[ "$CODE" =~ ^[0-9]+$ ]] || fail "versionCode '$CODE' is not a number"
[[ "$NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "versionName '$NAME' is not X.Y.Z"
[[ "$BASE_CODE" =~ ^[0-9]+$ && "$BASE_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
    fail "$BASE has no readable version in $VERSION_FILE"

[[ "$NAME" != "$BASE_NAME" && "$(printf '%s\n' "$BASE_NAME" "$NAME" | sort -V | tail -n 1)" == "$NAME" ]] ||
    fail "version $NAME is not newer than $BASE's $BASE_NAME: $BUMP_HINT"
((CODE > BASE_CODE)) ||
    fail "versionCode $CODE is not higher than $BASE's $BASE_CODE: $BUMP_HINT"

HEADING="$(grep -E "^## ${NAME//./\\.}( |$)" "$CHANGELOG" | head -n 1 || true)"
[[ -n "$HEADING" ]] || fail "$CHANGELOG has no '## $NAME' section: $BUMP_HINT"
NOTES="$(awk -v heading="$HEADING" '
    $0 == heading { on = 1; next }
    on && /^## / { exit }
    on { print }
' "$CHANGELOG" | sed -e '/./,$!d')"
[[ -n "${NOTES//[[:space:]]/}" ]] || fail "the '## $NAME' section of $CHANGELOG is empty"
! grep -q 'TODO' <<<"$NOTES" ||
    fail "the '## $NAME' section of $CHANGELOG still has a TODO: write the release notes"

echo "release_check: $BASE_NAME ($BASE_CODE) -> $NAME ($CODE), release notes written"
