# U25 review

## Scope and provenance

- Target branch: `codex/testflight-internal`
- Approved base: `1392a98ee16e517c2b223cd31d18287719641afc`
- Feature branch: `codex/u25-minimum-brightness`
- Production commit: `1bf784b453df7aba0f948959d977c422b84116d6`
- Upstream source: `9b09045f7af32073c8073893f7e324f135c8060a`, `fix(player): allow minimum device brightness`
- Upstream and imported stable patch ID: `1bb2eae63acae06a7fece7a87d3716d4a647ae78`
- Upstream tip rechecked from the official remote: `9bc77bc48e0cc4958006657f129190169d831e33`

The upstream production patch was cherry-picked with original authorship and the `-x` attribution trailer. It applied unchanged: there are no adapted or omitted hunks. The five changes are limited to the Android and iOS player gesture controllers:

- Android current-window brightness: `0.02f..1f` to `0f..1f`
- Android requested window brightness: `0.02f..1f` to `0f..1f`
- Android system-brightness fallback: `1..255` to `0..255`
- iOS current screen brightness: `0.02f..1f` to `0f..1f`
- iOS requested screen brightness: `0.02f..1f` to `0f..1f`

The upstream source is an ancestor of the checked upstream tip. Later upstream work touches these platform files for other player features, but the checked tip retains the same U25 brightness ranges; no additional commit is required for this fix.

The organizer handoff and audit were read as source material and left unchanged. No repository `AGENTS.md`, `plan.md`, or `PLAN.md` exists in this worktree.

## Behavior review

Before U25, both gesture controllers forced every returned or requested brightness to at least `0.02`. Android also converted a zero system setting to `1 / 255`, so zero could not be used as the gesture baseline. The imported patch permits the full `0.0` through `1.0` range on both platforms and preserves Android's inherited-brightness sentinel because negative window values still take the system-brightness path.

This matches the platform contracts: Android reserves negative `screenBrightness` for the inherited/default setting and accepts `0.0` through `1.0` for dark through full brightness, while UIKit documents `UIScreen.brightness` as `0.0` through `1.0`.

Primary references:

- [Android `WindowManager.LayoutParams.screenBrightness`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams)
- [Apple `UIScreen.brightness`](https://developer.apple.com/documentation/uikit/uiscreen/brightness?changes=l_2)

Gesture boundaries, direction, activation threshold, sensitivity, feedback, volume, playback, and every unrelated player path are unchanged. Player exit still restores the originally captured value: Android writes `BRIGHTNESS_OVERRIDE_NONE` when the original window inherited system brightness and otherwise restores the original `0f..1f` value; iOS restores the original `UIScreen.mainScreen.brightness` value.

No clamp-only unit test was added. Both controllers are private, and extracting production code only to restate `coerceIn` would change the exact upstream patch without proving platform-window or display behavior. Existing player tests, both platform compilations, full app builds, source-path review, and an Android window-state runtime check provide the evidence instead.

## Automated validation

All production validation below ran from exact production commit `1bf784b453df7aba0f948959d977c422b84116d6`. The later review-note commit changes only this Markdown file.

Dependencies were prepared with `scripts/prepare-ios-dependencies.sh`, which initialized MPVKit at the repository-recorded revision and installed the checksum-pinned Nuvio Engine `0.1.1` under ignored build output. Machine-local SDK configuration, signing configuration, logs, XML, virtual-device files, and runtime fixtures remain ignored.

### Android host player tests

Command, with the Android Studio JDK and installed Android SDK supplied through the environment:

```sh
./gradlew -Pnuvio.android.distribution=full :composeApp:testAndroidHostTest \
  --tests 'com.nuvio.app.features.player.*' \
  --tests 'com.nuvio.app.features.streams.StreamResumeStateTest' \
  --rerun-tasks --console=plain
```

Result: `BUILD SUCCESSFUL in 1m 42s`; 14 suites, 119 passed, 0 failed, 0 errors, 0 skipped.

Evidence:

- `build/u25-evidence/final-host/gradle.log`
- `build/u25-evidence/final-host/xml/`

### Kotlin/Native iOS Simulator player tests

Command, using the dedicated iPhone 17 Pro / iOS 26.5 simulator `E87B6297-ADC6-4547-BC68-0D1DA059D114`:

```sh
scripts/test-ios-player-regressions.sh \
  --tests 'com.nuvio.app.features.player.*' \
  --tests 'com.nuvio.app.features.streams.StreamResumeStateTest' \
  --rerun-tasks --console=plain
```

Result: `BUILD SUCCESSFUL in 2m 43s`; the same 14 suites and 119 tests passed with 0 failures, errors, or skips. The runner compiled the real Swift CryptoKit bridge into an arm64 Mach-O object. Native linking emitted the existing non-fatal external module-cache debug-symbol warnings.

Evidence:

- `build/u25-evidence/final-native/gradle.log`
- `build/u25-evidence/final-native/xml/`
- `build/ios-player-test-bridge/PluginCryptoBridge.o`

### Full-distribution Android app

```sh
./gradlew :androidApp:assembleFullDebug --console=plain
```

Result: `BUILD SUCCESSFUL in 2m 5s`; 63 tasks. The APK is `androidApp/build/outputs/apk/full/debug/androidApp-full-debug.apk`, application ID `com.nuviodebug.com`, version `0.4.12`, build `120`.

- APK SHA-256: `cc2215486298544f8bd817b27ed4861f88b173a73eed45fdaaa6d1850e936875`
- Evidence: `build/u25-evidence/final-android/gradle.log`

### Full-distribution iOS Simulator app

```sh
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -configuration Debug -sdk iphonesimulator \
  -destination 'id=E87B6297-ADC6-4547-BC68-0D1DA059D114' \
  -derivedDataPath build/u25-final-simulator \
  -disableAutomaticPackageResolution CODE_SIGNING_ALLOWED=NO build
```

Result: Xcode 26.6 / iOS 26.5 SDK completed with `** BUILD SUCCEEDED **`. The output is `build/u25-final-simulator/Build/Products/Debug-iphonesimulator/Nuvio.app`, bundle ID `com.tinykyuu.nuvio.internal`, version `0.4.12`, build `120`. It contains `NotoSansCJKsc-Regular.otf` and `DownloadsWidgetExtension.appex`.

- Launcher SHA-256: `428ddcea2061fac832d8cb4c5f7d7fa8036fab46fd26c133bcfa2be43d1eae41`
- Debug dylib SHA-256: `d73003131d973462abe9770615950a8dfc24dda7e8024ccc1387c7cb02128df3`
- CJK font SHA-256: `2c76254f6fc379fddfce0a7e84fb5385bb135d3e399294f6eeb6680d0365b74b`
- Evidence: `build/u25-evidence/final-ios/xcodebuild.log`

The built app was installed and launched successfully on the dedicated U25 simulator with process ID `86156`. The launch screenshot is `build/u25-evidence/runtime/ios-launch.png`. This is an app-launch smoke test only: the iOS Simulator does not provide evidence that `UIScreen.brightness` changes a physical display panel, so minimum, increase, and restoration are not claimed from it.

## Android emulator behavior check

The APK was installed only on a dedicated project-local Android 36 AVD, serial `emulator-5582`; the existing F01 emulator and its app data were not used. A loopback-only disposable add-on served a generated three-minute H.264/AAC video. The app loaded that stream through its normal source/player flow.

Window and UI evidence established the requested sequence:

1. Before the gesture, the app window had no `sbrt` override and inherited system brightness. The emulator setting was `102`.
2. Downward left-side gestures produced `Brightness 0%`; `dumpsys window windows` reported the app window as `sbrt=0.0`.
3. An upward gesture from zero produced `Brightness 24%`; the window reported `sbrt=0.23633711`.
4. Exiting the player removed the `sbrt` override, restoring inherited brightness. The system setting was still `102`, confirming that the player changed only the window override.

Evidence:

- `build/u25-evidence/runtime/android-min-feedback.png`
- `build/u25-evidence/runtime/android-raised-feedback.png`
- `build/u25-evidence/runtime/android-brightness-check.txt`

The emulator proves the Android `WindowManager.LayoutParams.screenBrightness` values, feedback, increase-from-zero behavior, and restore-to-inherited behavior. It does not substitute for observing the actual minimum backlight on a physical Android panel.

## Preservation check

The base-to-production diff changes only:

- `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerPlatformEffects.android.kt`
- `composeApp/src/iosMain/kotlin/com/nuvio/app/features/player/PlayerPlatformEffects.ios.kt`

It does not change app identity, version/build, distribution, signing, Compose, CryptoKit, CJK fonts, metadata or certification behavior, downloads/offline Library, subtitles, audio, keyboard/trackpad handling, resume, Watch Together, or any organizer file. `git diff --check` passes. This review-note commit adds documentation only.

## Physical-device checks and stop point

No physical-device installation, release/archive build, TestFlight upload, merge, branch deletion, or U20 work was performed.

Before release, verify separately on a physical iPhone and Android phone:

- a downward brightness gesture reaches the device's true minimum;
- an upward gesture raises brightness again from minimum;
- leaving the player restores the exact pre-player brightness;
- Android behavior remains correct when the window initially inherits system brightness and when it starts with an explicit override.

Stop for organizer/user review after the U25 PR is published.
