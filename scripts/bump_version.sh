#!/usr/bin/env bash
# Starts the next release on a pull request's branch (`make bump [BUMP=patch|minor|major]`):
#
# 1. version.properties: versionCode + 1, and versionName bumped (patch by default).
# 2. CHANGELOG.md: a new `## <version>` section above the previous release's, with a TODO line
#    to replace with the release notes. `make publish` refuses to publish while it is there.
#
# Commit both with the pull request, before it is merged (`make release_check` and CI's
# `release ready` check verify it). Merging it to master is the release; after the merge,
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
BRANCH="$(git branch --show-current)"
NEW_BRANCH_HINT="start a branch from master and bump there: git fetch origin && \
git switch --no-track -c release/<version> origin/master"
[[ "$BRANCH" != "master" ]] ||
    fail "bump on the pull request's branch: master only takes merges; $NEW_BRANCH_HINT"
# The bump must be merged with its pull request: on a branch whose pull request is already
# merged it would never reach master (0.3.5's bump was first committed that way). Checked when gh
# can see the repository (e.g. `env -u GITHUB_TOKEN make bump`); otherwise it says it skipped.
if command -v gh >/dev/null; then
    GH_ERROR="$(mktemp)"
    PR_STATE="$(gh pr view "$BRANCH" --json state,number --jq '"\(.state) #\(.number)"' \
        2>"$GH_ERROR" || true)"
    # A branch without a pull request yet is fine; any other gh failure means it was not checked.
    if [[ -z "$PR_STATE" ]] && ! grep -qi "no pull requests found" "$GH_ERROR"; then
        echo "bump: warning: could not check whether the pull request of '$BRANCH' is already" \
            "merged ($(head -n 1 "$GH_ERROR")). Check it, or run: env -u GITHUB_TOKEN make bump" >&2
    fi
    rm -f "$GH_ERROR"
    [[ "$PR_STATE" != MERGED* ]] ||
        fail "the pull request of '$BRANCH' (${PR_STATE#MERGED }) is already merged, so master \
would never get this bump; $NEW_BRANCH_HINT"
fi
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
echo "  2. Commit $VERSION_FILE and $CHANGELOG on this branch, run 'make release_check', and push"
echo "     before the pull request is merged (CI's 'release ready' check fails without them)."
echo "  3. After the merge: make publish"
