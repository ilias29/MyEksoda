#!/usr/bin/env bash
# Starts the next release on a pull request's branch (`make bump [BUMP=patch|minor|major]`):
#
# 1. version.properties: versionCode + 1, and versionName bumped (patch by default).
# 2. CHANGELOG.md: a new `## <version>` section above the previous release's, with a TODO line
#    to replace with the release notes. `make publish` refuses to publish while it is there.
#
# Commit both with the pull request. Merging it to master is the release; after the merge,
# `make publish` (scripts/publish_release.sh) builds and publishes it.
set -euo pipefail
cd "$(dirname "$0")/.."

BUMP="${1:-patch}"
VERSION_FILE="version.properties"
CHANGELOG="CHANGELOG.md"
TODO_LINE="- TODO: what changed, written for the people who use the app."

fail() {
    echo "bump: $*" >&2
    exit 1
}

property() { sed -n "s/^$1=//p" "$2" | tail -n 1; }

case "$BUMP" in patch | minor | major) ;; *) fail "BUMP must be patch, minor or major" ;; esac
[[ "$(git branch --show-current)" != "master" ]] ||
    fail "bump on the pull request's branch: master only takes merges"
[[ -f "$CHANGELOG" ]] || fail "$CHANGELOG is missing"

OLD_CODE="$(property versionCode "$VERSION_FILE")"
OLD_NAME="$(property versionName "$VERSION_FILE")"
[[ "$OLD_CODE" =~ ^[0-9]+$ ]] || fail "versionCode '$OLD_CODE' is not a number"
[[ "$OLD_NAME" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || fail "versionName '$OLD_NAME' is not X.Y.Z"
MAJOR="${BASH_REMATCH[1]}"
MINOR="${BASH_REMATCH[2]}"
PATCH="${BASH_REMATCH[3]}"
case "$BUMP" in
    major) NEW_NAME="$((MAJOR + 1)).0.0" ;;
    minor) NEW_NAME="$MAJOR.$((MINOR + 1)).0" ;;
    patch) NEW_NAME="$MAJOR.$MINOR.$((PATCH + 1))" ;;
esac
NEW_CODE="$((OLD_CODE + 1))"

! grep -qE "^## ${NEW_NAME//./\\.}( |$)" "$CHANGELOG" ||
    fail "$CHANGELOG already has a '## $NEW_NAME' section"

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

sed -e "s/^versionCode=.*/versionCode=$NEW_CODE/" \
    -e "s/^versionName=.*/versionName=$NEW_NAME/" "$VERSION_FILE" >"$TMP"
cat "$TMP" >"$VERSION_FILE"

# The new section goes right above the first `## ` heading (the previous release).
awk -v heading="## $NEW_NAME" -v todo="$TODO_LINE" '
    !done && /^## / { print heading; print ""; print todo; print ""; done = 1 }
    { print }
    END { if (!done) { print ""; print heading; print ""; print todo } }
' "$CHANGELOG" >"$TMP"
cat "$TMP" >"$CHANGELOG"

echo "bump: $OLD_NAME ($OLD_CODE) -> $NEW_NAME ($NEW_CODE)"
echo
echo "Next:"
echo "  1. Write the release notes in $CHANGELOG under '## $NEW_NAME', replacing the TODO line."
echo "     An optional title goes on the heading: '## $NEW_NAME - Household sync'."
echo "  2. Commit $VERSION_FILE and $CHANGELOG with the pull request, and merge it."
echo "  3. make publish"
