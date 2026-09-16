# F04 review: responsive local poster sizing

## Status

Ready for organizer review. This branch is based on `codex/testflight-internal` at `249332335c9ce235f2dda93f8a7c6ec993046e8e`. It adapts upstream `cf4674a81c88eade150f12a27f7313b1296ccea1` to the current fork without changing F03 trackpad behavior or F05 bottom navigation.

## What changed

- Catalog posters and Continue Watching Poster cards now use the same effective width, 2:3 height, and corner radius.
- Automatic is the default size mode. It resolves to Balanced (`126 x 189`) on phones, Large (`140 x 210`) on tablets with at least 700dp of usable width, and Balanced below that tablet width.
- The fixed choices remain Compact, Dense, Standard, Balanced, Comfort, and Large. Extra Large (`160 x 240`) is now available.
- Size mode and the manual override use installation-local storage. Profile switching and remote profile sync continue to control the other poster style fields but cannot overwrite local size.
- Legacy default or Balanced width migrates to Automatic. A non-default legacy width migrates to a local manual override. The new local payload makes the migration idempotent.
- Continue Watching now defaults to Poster. Every legacy version-0 style, including Card and Wide, migrates once to Poster. Remotely restored legacy payloads follow the same path and write version 1 back to profile sync. After version 1, explicit Card, Wide, or Poster choices remain unchanged.
- Continue Watching progress and Wide layouts derive from the effective poster size with guarded minimum dimensions.
- PR20 offline Home behavior remains in place. F04 only changes layout inputs and does not replace the fork's Home data or offline paths.

## Automated validation

All commands were run from the worktree root.

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ANDROID_HOME='/Users/muharrem/Library/Android/sdk' ./gradlew -Pnuvio.ios.distribution=appstore -Pnuvio.android.distribution=full :composeApp:testAndroidHostTest --rerun-tasks --console=plain
```

Result: `BUILD SUCCESSFUL`. The suite contains 965 tests with 0 failures, 0 errors, and 0 skipped tests. F04 coverage includes Automatic phone/tablet/narrow resolution, every fixed 2:3 preset, local migration and restart idempotence, profile/remote isolation, Continue Watching sizing and safe bounds, and the version-0 Card/Wide-to-Poster migration rules.

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ANDROID_HOME='/Users/muharrem/Library/Android/sdk' ./gradlew -Pnuvio.ios.distribution=appstore -Pnuvio.android.distribution=full :androidApp:assembleFullDebug --console=plain
```

Result: `BUILD SUCCESSFUL`. Artifact: `androidApp/build/outputs/apk/full/debug/androidApp-full-debug.apk`. SHA-256: `ab12946129a8f2ad039c73319a1bc297c22bee4ffd5fbec5284373866f6e8d0b`.

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ANDROID_HOME='/Users/muharrem/Library/Android/sdk' NUVIO_ENGINE_ROOT='/Users/muharrem/Documents/ChatGPT/Nuvio iOS/build/nuvio-engine' ./gradlew -Pnuvio.ios.distribution=full -Pnuvio.android.distribution=full :composeApp:compileKotlinIosSimulatorArm64 --console=plain
```

Result: `BUILD SUCCESSFUL`.

```sh
NUVIO_IOS_DISTRIBUTION=full NUVIO_ENGINE_ROOT='/Users/muharrem/Documents/ChatGPT/Nuvio iOS/build/nuvio-engine' JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -sdk iphonesimulator -destination 'id=68A42C7D-B136-4518-A02B-F4CED41E2986' -derivedDataPath build/f04-ios-simulator -disableAutomaticPackageResolution CODE_SIGNING_ALLOWED=NO build
```

Result: `** BUILD SUCCEEDED **`.

## Simulator observations

- `Nuvio F04 Phone`, iOS 26.5, `68A42C7D-B136-4518-A02B-F4CED41E2986`: the app installed and launched in an isolated local-only profile. Home rendered its no-addon state in portrait. Rotation to landscape completed without a crash, and Settings changed to its wide layout. Automatic phone sizing is covered by the resolver test at `126 x 189` because the phone Poster Card Style leaf could not be reached reliably through the simulator's touch scrolling automation.
- `Nuvio F04 Tablet`, iOS 26.5, `DC2F9462-EC7F-4C5A-9D56-CFE8AD62A41E`: the app installed and launched in an isolated local-only profile. The Poster Card Style page showed Automatic selected at `140 x 210`. Selecting Extra Large updated the preview to `160 x 240`; selecting Automatic again restored `140 x 210`. The device-local and narrow-window guidance was visible.
- The clean simulator profiles had no active addon or Continue Watching data, so live media rows were not available. Shared catalog/Continue Watching dimensions and safe Wide/progress bounds are covered by focused tests.
- A narrow iPad split-view session was not practical in this simulator run. The centralized resolver is tested immediately below and at the 700dp boundary (`699dp -> 126 x 189`, `700dp -> 140 x 210`).

## Deferred

- No physical-device validation was performed.
- Organizer review should focus on the 700dp tablet threshold, the installation-local storage boundary, and the remote Continue Watching migration write-back.
