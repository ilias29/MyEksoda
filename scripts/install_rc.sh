#!/usr/bin/env bash
# Builds the release APK and installs it on one device (`make install_rc [DEVICE=<serial>]`).
#
# Gradle's installRelease installs on every connected device at once and fails on the first that
# refuses, with a long stack trace. This installs on the one device chosen (the only one
# connected, else DEVICE or ANDROID_SERIAL) and explains the two usual refusals:
# - the installed app is signed with another key (an old build made with the debug key);
# - the phone blocks installs over USB (Xiaomi HyperOS/MIUI: "Install via USB").
set -euo pipefail
cd "$(dirname "$0")/.."

PACKAGE="io.eksoda"
APK="app/build/outputs/apk/release/app-release.apk"

fail() {
    echo "install_rc: $*" >&2
    exit 1
}

property() { sed -n "s/^$1=//p" "$2" 2>/dev/null | tail -n 1; }

SDK_DIR="${ANDROID_HOME:-$(property sdk.dir local.properties)}"
SDK_DIR="${SDK_DIR:-$HOME/Library/Android/sdk}"
ADB="$SDK_DIR/platform-tools/adb"
[[ -x "$ADB" ]] || fail "adb not found at $ADB"

# --- The device --------------------------------------------------------------------------------

DEVICE="${1:-${ANDROID_SERIAL:-}}"
if [[ -z "$DEVICE" ]]; then
    CONNECTED=()
    while IFS= read -r serial; do CONNECTED+=("$serial"); done < <(
        "$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }'
    )
    case ${#CONNECTED[@]} in
        0) fail "no device connected (adb devices lists none ready)" ;;
        1) DEVICE="${CONNECTED[0]}" ;;
        *)
            echo "install_rc: several devices are connected; choose one with DEVICE=<serial>:" >&2
            "$ADB" devices -l | awk 'NR > 1 && $2 == "device"' | sed 's/^/  /' >&2
            exit 1
            ;;
    esac
fi

# --- Build and install -------------------------------------------------------------------------

./gradlew :app:assembleRelease

echo "install_rc: installing on $DEVICE"
if OUTPUT="$("$ADB" -s "$DEVICE" install -r "$APK" 2>&1)"; then
    echo "$OUTPUT" | tail -n 1
    exit 0
fi

echo "$OUTPUT" | tail -n 1 >&2
case "$OUTPUT" in
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
        cat >&2 <<EOF

The $PACKAGE already on $DEVICE is signed with a different key, usually an old build made with
the debug key before the release key existed. Android installs an update only over the same key.
Uninstall it first (this deletes the app's data on that device; export a backup first if needed):

  $ADB -s $DEVICE uninstall $PACKAGE
EOF
        ;;

    *INSTALL_FAILED_USER_RESTRICTED*)
        cat >&2 <<EOF

The phone refused the install. On Xiaomi (HyperOS/MIUI): Settings > Additional settings >
Developer options, turn on "Install via USB" (and "USB debugging (Security settings)"), then run
this again and tap Install on the phone's prompt within a few seconds.
EOF
        ;;
esac
exit 1
