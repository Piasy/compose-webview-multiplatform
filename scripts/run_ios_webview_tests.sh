#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)
RESULTS_DIR=${IOS_WEBVIEW_TEST_RESULTS_DIR:-"$REPO_ROOT/build/ios-test-results"}
DERIVED_DATA=${IOS_WEBVIEW_TEST_DERIVED_DATA:-"$REPO_ROOT/build/ios-test-derived-data"}
RESULT_BUNDLE=${IOS_WEBVIEW_TEST_RESULT_BUNDLE:-"$RESULTS_DIR/iosAppTests-$(date +%Y%m%d-%H%M%S).xcresult"}

mkdir -p "$RESULTS_DIR" "$DERIVED_DATA"

SIMULATOR_UDID=$(
    xcrun simctl list devices available -j | python3 -c '
import json
import sys

devices = [
    device
    for runtime_devices in json.load(sys.stdin)["devices"].values()
    for device in runtime_devices
    if "iPhone" in device.get("deviceTypeIdentifier", "")
]
selected = next((device for device in devices if device.get("state") == "Booted"), None)
selected = selected or next(iter(devices), None)
if selected is None:
    raise SystemExit("No available iPhone Simulator found")
print(selected["udid"])
'
)

if [[ $(xcrun simctl list devices -j | python3 -c "import json,sys; print(next(device['state'] for devices in json.load(sys.stdin)['devices'].values() for device in devices if device['udid'] == '$SIMULATOR_UDID'))") != "Booted" ]]; then
    xcrun simctl boot "$SIMULATOR_UDID"
fi
xcrun simctl bootstatus "$SIMULATOR_UDID" -b

xcodebuild test \
    -project "$REPO_ROOT/sample/iosApp/iosApp.xcodeproj" \
    -scheme iosApp \
    -configuration Debug \
    -destination "platform=iOS Simulator,id=$SIMULATOR_UDID" \
    -derivedDataPath "$DERIVED_DATA" \
    -resultBundlePath "$RESULT_BUNDLE" \
    CODE_SIGNING_ALLOWED=NO

echo "iOS WebView test result bundle: $RESULT_BUNDLE"
