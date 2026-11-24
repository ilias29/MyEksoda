#!/usr/bin/env bash
# Publishes the release just merged to master (`make publish`). Every merge to master is a
# release: its pull request bumped the version and wrote the notes with `make bump`.
#
# 1. Checks before anything changes: a clean working tree, master up to date with origin, the
#    release key, gh logged in with push access to the repository, a version newer than the last
#    release, and its CHANGELOG.md section (without the TODO `make bump` puts there).
# 2. Asks for confirmation.
# 3. `make check`, then the release APK, signed with the release key and checked against it,
#    copied to the root as eksoda-<version>.apk (replacing the previous release's APK).
# 4. Tags master v<version>, pushes the tag, and creates the GitHub release: the changelog
#    section and the checksums as its notes, the APK attached.
#
# It runs on master and returns to the branch it was started from, also after a failure or a No.
# After a failure, run it again: a tag already on this commit is reused, and a release an earlier
# run left as a draft or without its APK is finished. Nothing is committed.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION_FILE="version.properties"
CHANGELOG="CHANGELOG.md"
KEY_PROPS="${EKSODA_KEYSTORE:-$HOME/.eksoda/keystore.properties}"
KEYTOOL="${JAVA_HOME:?JAVA_HOME is not set (run through make)}/bin/keytool"
BUILT_APK="app/build/outputs/apk/release/app-release.apk"

fail() {
    echo "publish: $*" >&2
    exit 1
}

property() { sed -n "s/^$1=//p" "$2" | tail -n 1; }

# --- 1. Checks ---------------------------------------------------------------------------------

[[ -f "$KEY_PROPS" ]] || fail "no release key at $KEY_PROPS. Run 'make release_key' once."
command -v gh >/dev/null || fail "gh (GitHub CLI) is not installed: brew install gh"

SDK_DIR="${ANDROID_HOME:-$(property sdk.dir local.properties 2>/dev/null || true)}"
SDK_DIR="${SDK_DIR:-$HOME/Library/Android/sdk}"
APKSIGNER="$(find "$SDK_DIR/build-tools" -maxdepth 2 -name apksigner 2>/dev/null | sort -V | tail -n 1)"
[[ -n "$APKSIGNER" ]] || fail "apksigner not found under $SDK_DIR/build-tools"

# Release APKs in the root are expected (they are never committed); anything else is not.
DIRTY="$(git status --porcelain | grep -vE '^\?\? eksoda-[^/]*\.apk$' || true)"
[[ -z "$DIRTY" ]] || fail "the working tree has changes; commit or stash them first:
$DIRTY"

START_BRANCH="$(git branch --show-current)"
NOTES_FILE=""
cleanup() {
    [[ -z "$NOTES_FILE" ]] || rm -f "$NOTES_FILE"
    if [[ -n "$START_BRANCH" && "$START_BRANCH" != "master" &&
        "$(git branch --show-current)" == "master" ]]; then
        git switch --quiet "$START_BRANCH" && echo "publish: back on $START_BRANCH" >&2
    fi
}
trap cleanup EXIT

git switch --quiet master
git pull --quiet --ff-only origin master
git fetch --quiet --tags origin
HEAD_SHA="$(git rev-parse HEAD)"

REPO="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
if [[ "$(gh api "repos/$REPO" --jq .permissions.push 2>/dev/null)" != "true" ]]; then
    fail "gh is logged in as '$(gh api user --jq .login 2>/dev/null || echo unknown)', which cannot \
publish releases on $REPO. Log in with an account that can (gh auth login, gh auth switch); a \
GITHUB_TOKEN or GH_TOKEN in the environment takes precedence over the login."
fi

CODE="$(property versionCode "$VERSION_FILE")"
NAME="$(property versionName "$VERSION_FILE")"
[[ "$CODE" =~ ^[0-9]+$ ]] || fail "versionCode '$CODE' is not a number"
[[ "$NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "versionName '$NAME' is not X.Y.Z"
TAG="v$NAME"
OUTPUT="eksoda-$NAME.apk"

# A release an interrupted run left behind (a draft, or without the APK) is finished, not refused.
RESUME=""
if RELEASE_STATE="$(gh release view "$TAG" --repo "$REPO" --json isDraft,assets \
    --jq "\"\(.isDraft) \(any(.assets[]; .name == \"$OUTPUT\"))\"" 2>/dev/null)"; then
    if [[ "$RELEASE_STATE" == "false true" ]]; then
        # Commits merged since the tag without a bump: a pull request merged without it, or one
        # labeled "no release" (.github/workflows/release.yml).
        UNRELEASED="$(git rev-list --count "refs/tags/$TAG..HEAD" 2>/dev/null || echo 0)"
        ((UNRELEASED == 0)) ||
            fail "master has $UNRELEASED commit(s) since $TAG, but its version is still $NAME: they \
were merged without a version bump. Release them with a pull request of its own: git fetch origin \
&& git switch --no-track -c release/<version> origin/master && make bump BUMP=patch|minor|major, \
write the notes, make release_check, commit, push, merge, then make publish."
        fail "$TAG is already published. The next release needs its version bumped in a pull \
request first: make bump BUMP=patch|minor|major"
    fi
    RESUME=1
fi

# The last release: the highest X.Y.Z among the tags (older ones are named v.X.Y.Z).
LAST_TAG="$(git tag --list | sed -nE 's/^v\.?([0-9]+\.[0-9]+\.[0-9]+)$/\1 &/p' |
    grep -v " $TAG\$" | sort -V -k 1,1 | tail -n 1 | cut -d ' ' -f 2 || true)"
if [[ -n "$LAST_TAG" ]]; then
    LAST_NAME="$(sed -E 's/^v\.?//' <<<"$LAST_TAG")"
    [[ "$NAME" != "$LAST_NAME" && "$(printf '%s\n' "$LAST_NAME" "$NAME" | sort -V | tail -n 1)" == "$NAME" ]] ||
        fail "version $NAME is not newer than the last release, $LAST_NAME (tag $LAST_TAG)"
    LAST_CODE="$(git show "$LAST_TAG:$VERSION_FILE" 2>/dev/null | sed -n 's/^versionCode=//p' | tail -n 1 || true)"
    if [[ "$LAST_CODE" =~ ^[0-9]+$ ]] && ((CODE <= LAST_CODE)); then
        fail "versionCode $CODE is not higher than $LAST_CODE, the last release's"
    fi
fi

if git rev-parse --quiet --verify "refs/tags/$TAG" >/dev/null; then
    [[ "$(git rev-list -n 1 "$TAG")" == "$HEAD_SHA" ]] ||
        fail "tag $TAG already exists on another commit ($(git rev-list -n 1 "$TAG"))"
fi

HEADING="$(grep -E "^## ${NAME//./\\.}( |$)" "$CHANGELOG" | head -n 1 || true)"
[[ -n "$HEADING" ]] || fail "$CHANGELOG has no '## $NAME' section with the release notes"
NOTES="$(awk -v heading="$HEADING" '
    $0 == heading { on = 1; next }
    on && /^## / { exit }
    on { print }
' "$CHANGELOG" | sed -e '/./,$!d')"
[[ -n "${NOTES//[[:space:]]/}" ]] || fail "the '## $NAME' section of $CHANGELOG is empty"
! grep -q 'TODO' <<<"$NOTES" || fail "the '## $NAME' section of $CHANGELOG still has a TODO"
TITLE="Release $TAG${HEADING#"## $NAME"}"

# --- 2. Confirmation ---------------------------------------------------------------------------

echo "publish: $TITLE"
echo "  versionCode $CODE, from master at $(git log -1 --format='%h %s')"
echo "  on $REPO, after ${LAST_TAG:-no earlier release}"
[[ -z "$RESUME" ]] || echo "  finishing the release an earlier run left unfinished"
read -r -p "Build and publish it? [y/N] " ANSWER
[[ "$ANSWER" =~ ^[Yy]$ ]] || fail "nothing published"

# --- 3. Checks and the signed APK --------------------------------------------------------------

make check
./gradlew :app:assembleRelease

EKSODA_KEY_PASSWORD="$(property storePassword "$KEY_PROPS")"
export EKSODA_KEY_PASSWORD
KEY_SHA256="$("$KEYTOOL" -list -v -keystore "$(property storeFile "$KEY_PROPS")" \
    -alias "$(property keyAlias "$KEY_PROPS")" -storepass:env EKSODA_KEY_PASSWORD |
    sed -n 's/^[[:space:]]*SHA256: //p' | tr -d ':' | tr '[:upper:]' '[:lower:]')"
APK_SHA256="$("$APKSIGNER" verify --print-certs "$BUILT_APK" |
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
[[ -n "$KEY_SHA256" && "$APK_SHA256" == "$KEY_SHA256" ]] ||
    fail "the APK is not signed with the release key (APK: ${APK_SHA256:-none})"

# Only the current release's APK stays in the root.
for apk in eksoda-*.apk; do
    if [[ -e "$apk" && "$apk" != "$OUTPUT" ]]; then
        rm -f "$apk"
        echo "publish: removed the previous APK, $apk"
    fi
done
cp "$BUILT_APK" "$OUTPUT"
FILE_SHA256="$(shasum -a 256 "$OUTPUT" | cut -d ' ' -f 1)"

# --- 4. Tag and release ------------------------------------------------------------------------

NOTES_FILE="$(mktemp)"
{
    printf '%s\n\n---\n\n' "$NOTES"
    echo "\`$OUTPUT\`, version $NAME ($CODE)"
    echo
    echo "- SHA-256 of the file: \`$FILE_SHA256\`"
    echo "- Signing certificate (SHA-256): \`$APK_SHA256\`"
} >"$NOTES_FILE"

git rev-parse --quiet --verify "refs/tags/$TAG" >/dev/null || git tag -a "$TAG" -m "Release $NAME"
git push --quiet origin "refs/tags/$TAG"
if [[ -n "$RESUME" ]]; then
    gh release upload "$TAG" "$OUTPUT" --repo "$REPO" --clobber
    gh release edit "$TAG" --repo "$REPO" --title "$TITLE" --notes-file "$NOTES_FILE" \
        --draft=false
else
    gh release create "$TAG" "$OUTPUT" --repo "$REPO" --verify-tag --title "$TITLE" \
        --notes-file "$NOTES_FILE"
fi

echo
echo "Published $TITLE: $OUTPUT"
echo "  SHA-256 of the file:  $FILE_SHA256"
echo "  Signing certificate:  $APK_SHA256"
