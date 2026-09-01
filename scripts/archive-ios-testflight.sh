#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
build_number="${IOS_BUILD_NUMBER:-119}"
archive_path="${IOS_ARCHIVE_PATH:-${repository_root}/build/Nuvio-Internal-${build_number}.xcarchive}"
export_path="${IOS_EXPORT_PATH:-${repository_root}/build/testflight-upload-${build_number}}"
upload=false

if [[ "${1:-}" == "--upload" ]]; then
    upload=true
elif [[ -n "${1:-}" ]]; then
    echo "Usage: $0 [--upload]" >&2
    exit 1
fi

if [[ ! "${build_number}" =~ ^[1-9][0-9]*$ ]]; then
    echo "IOS_BUILD_NUMBER must be a positive integer." >&2
    exit 1
fi

cd "${repository_root}"
./scripts/configure-official-nuvio-server.sh
./scripts/prepare-ios-dependencies.sh

env NUVIO_IOS_DISTRIBUTION=full \
    xcodebuild \
    -project iosApp/iosApp.xcodeproj \
    -scheme iosApp \
    -configuration Release \
    -destination 'generic/platform=iOS' \
    -archivePath "${archive_path}" \
    -allowProvisioningUpdates \
    CURRENT_PROJECT_VERSION="${build_number}" \
    archive

echo "Created ${archive_path}"

app_bundle="$(find "${archive_path}/Products/Applications" -maxdepth 1 -type d -name '*.app' -print -quit)"
if [[ -z "${app_bundle}" ]]; then
    echo "Archived app bundle was not found." >&2
    exit 1
fi
app_executable_name="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "${app_bundle}/Info.plist")"
./scripts/verify-ios-app-store-symbols.sh "${app_bundle}/${app_executable_name}"

if [[ "${upload}" == false ]]; then
    echo "Run $0 --upload to archive and upload an internal-only TestFlight build."
    exit 0
fi

xcodebuild \
    -exportArchive \
    -archivePath "${archive_path}" \
    -exportPath "${export_path}" \
    -exportOptionsPlist iosApp/Configuration/TestFlightInternalExportOptions.plist \
    -allowProvisioningUpdates

echo "Uploaded the internal-only TestFlight build to App Store Connect."
