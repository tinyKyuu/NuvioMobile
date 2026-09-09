#!/usr/bin/env bash
# Standalone full-distribution player tests need the same public Swift crypto
# bridge linked by the Xcode app. Run prepare-ios-dependencies.sh first.
# Optional: NUVIO_TEST_SIMULATOR_ID selects an existing simulator by UDID.
# Supply Gradle --tests filters and -Pnuvio.download.test.baseUrl as needed.
set -euo pipefail
repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "$repository_root"
export NUVIO_IOS_DISTRIBUTION=full
bridge_dir="$repository_root/build/ios-player-test-bridge"
mkdir -p "$bridge_dir"
sdk_path="$(xcrun --sdk iphonesimulator --show-sdk-path)"
swift_compiler="$(xcrun --sdk iphonesimulator --find swiftc)"
swift_lib_dir="$(cd "$(dirname "$swift_compiler")/../lib/swift/iphonesimulator" && pwd -P)"
"$swift_compiler" -sdk "$sdk_path" -parse-as-library -emit-object \
    -target arm64-apple-ios16.1-simulator \
    iosApp/iosApp/PluginCryptoBridge.swift -o "$bridge_dir/PluginCryptoBridge.o"
cat > "$bridge_dir/link-bridge.gradle" <<'GRADLE'
gradle.afterProject { p ->
    if (p.path == ':composeApp') {
        p.extensions.getByName('kotlin').targets.getByName('iosSimulatorArm64').binaries.all { binary ->
            if (binary.name.toLowerCase().contains('test')) {
                binary.linkerOpts(
                    new File(p.rootDir, 'build/ios-player-test-bridge/PluginCryptoBridge.o').absolutePath,
                    '-L' + System.getProperty('nuvio.test.swift.libdir'),
                    '-L' + System.getProperty('nuvio.test.sdk') + '/usr/lib/swift'
                )
            }
        }
        p.tasks.matching { it.name == 'iosSimulatorArm64Test' }.configureEach {
            outputs.upToDateWhen { false }
            def simulator = System.getenv('NUVIO_TEST_SIMULATOR_ID')
            if (simulator) device.set(simulator)
        }
    }
}
GRADLE
if [[ $# -eq 0 ]]; then
    set -- --tests 'com.nuvio.app.features.player.*' \
        --tests 'com.nuvio.app.features.streams.StreamResumeStateTest'
fi
exec ./gradlew -I "$bridge_dir/link-bridge.gradle" \
    "-Dnuvio.test.swift.libdir=$swift_lib_dir" "-Dnuvio.test.sdk=$sdk_path" \
    :composeApp:iosSimulatorArm64Test "$@"
