# F05 Review — Bottom Tablet Navigation Dock

## Integration base and public documentation

- The branch now includes stable integration commit `2f8f7c674bed24394ae408c57bfbce40cb636abc`, which contains PR22/F04 and the public README/feature-ledger baseline.
- The integration merge completed cleanly as `910d5458353b3d57416dc5104b1fddbd2ac9c585`. F05's code diff remains limited to navigation, tablet root insets, Search/Library header spacing, and the fourth-tab label.
- `README.md` now gives concise user-facing F04 and F05 entries under Navigation and appearance.
- `Docs/feature-status.md` records PR22 provenance and migration/storage boundaries, PR23 platform evidence and deferrals, and the reviewed stable base.
- `CONTRIBUTING.md` now requires behavior and UI pull requests to assess the public ledger and reserves the README for current user-facing fork distinctions and major scope.
- The PR23 entries use `Merged` because these public documents land in the same merge as F05. The pull request itself remains open pending organizer approval.

## Scope delivered

- Moves the existing floating tablet root navigation from top-center to bottom-center on iPad and Android tablets.
- Reserves bottom overlay space for root content and removes the former tablet top overlay.
- Keeps the dock above platform safe areas and gesture indicators.
- Changes the visible fourth root-tab label to `Settings` while preserving the avatar, profile selection, and add-profile behavior.
- Keeps the offline Retry control at the logical top end.
- Leaves phone/native navigation behavior unchanged apart from the approved `Settings` label.
- Removes the duplicate tablet status-bar inset above the Search and Library sticky headers while preserving the existing phone default.

## Automated validation

All commands ran from the F05 worktree with the checked-in Gradle wrapper.

### Focused common navigation tests on Android host

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/muharrem/Library/Android/sdk' \
./gradlew -Pnuvio.ios.distribution=appstore -Pnuvio.android.distribution=playstore \
  :composeApp:testAndroidHostTest --tests 'com.nuvio.app.MainTabsDestinationTest'
```

Result: `BUILD SUCCESSFUL` in 57s.

### Full common/Android-host test suite

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/muharrem/Library/Android/sdk' \
./gradlew -Pnuvio.ios.distribution=appstore -Pnuvio.android.distribution=playstore \
  :composeApp:testAndroidHostTest
```

Result: `BUILD SUCCESSFUL` in 39s.

### Focused iOS simulator tests

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/muharrem/Library/Android/sdk' \
./gradlew -Pnuvio.ios.distribution=appstore \
  :composeApp:iosSimulatorArm64Test --tests 'com.nuvio.app.MainTabsDestinationTest'
```

Result: `BUILD SUCCESSFUL` in 1m 11s. The linker emitted pre-existing missing module-cache debug-information warnings from `cryptography-kotlin`; tests passed.

### Android build

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/muharrem/Library/Android/sdk' \
./gradlew -Pnuvio.ios.distribution=appstore -Pnuvio.android.distribution=playstore \
  :androidApp:assembleDebug
```

Result: `BUILD SUCCESSFUL` in 1m 34s. Both full and Play Store debug APK variants assembled.

### iOS build

```sh
NUVIO_IOS_DISTRIBUTION=appstore xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,id=98F65EB4-873C-4470-ABCB-4A44F4668A15' \
  -derivedDataPath /private/tmp/nuvio-f05-derived \
  build CODE_SIGNING_ALLOWED=NO
```

Result: Kotlin framework `BUILD SUCCESSFUL` in 3m 2s; Xcode `** BUILD SUCCEEDED **`.

The app-store/play-store distribution flags were used because this checkout does not have the optional local Nuvio Engine Apple XCFramework. `MPVKit` was initialized at the repository-pinned submodule revision before the iOS build.

### Review follow-up: Search and Library header spacing

After review identified extra space above the Search and Library headers, both tablet paths were changed to keep the normal 10dp screen spacing outside the sticky header while letting the header own the single physical status-bar inset. Phone paths continue to pass the existing default padding.

The focused Android-host navigation test was rerun and passed in 56s. The focused iOS simulator navigation test was rerun and passed in 1m 42s. The iOS simulator app was rebuilt successfully with this command:

```sh
NUVIO_IOS_DISTRIBUTION=appstore xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,id=2F677371-F32F-4D4A-8D32-B371B6FC73C9' \
  -derivedDataPath /private/tmp/nuvio-f05-derived \
  build CODE_SIGNING_ALLOWED=NO
```

Result: Kotlin framework `BUILD SUCCESSFUL` in 1m 27s; Xcode `** BUILD SUCCEEDED **`.

### Combined F04/F05 base revalidation

After merging stable integration commit `2f8f7c674bed24394ae408c57bfbce40cb636abc`, the full Android host suite was forced to rerun:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/muharrem/Library/Android/sdk' \
./gradlew -Pnuvio.ios.distribution=appstore -Pnuvio.android.distribution=playstore \
  :composeApp:testAndroidHostTest --rerun-tasks --console=plain
```

Result: `BUILD SUCCESSFUL` in 1m 31s. The XML results contain 967 tests with 0 failures, 0 errors, and 0 skipped tests.

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/muharrem/Library/Android/sdk' \
./gradlew -Pnuvio.ios.distribution=appstore -Pnuvio.android.distribution=playstore \
  :androidApp:assembleDebug --console=plain
```

Result: `BUILD SUCCESSFUL` in 1m 28s. Both full and Play Store debug variants assembled.

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
./gradlew -Pnuvio.ios.distribution=appstore -Pnuvio.android.distribution=playstore \
  :composeApp:compileKotlinIosSimulatorArm64 --console=plain
```

Result: `BUILD SUCCESSFUL` in 40s.

```sh
NUVIO_IOS_DISTRIBUTION=appstore \
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -configuration Debug -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,id=DC2F9462-EC7F-4C5A-9D56-CFE8AD62A41E' \
  -derivedDataPath /private/tmp/nuvio-f05-combined-derived \
  build CODE_SIGNING_ALLOWED=NO
```

Result: Kotlin framework `BUILD SUCCESSFUL` in 1m 34s; Xcode `** BUILD SUCCEEDED **` for the booted `Nuvio F04 Tablet` iPad simulator. The linker emitted the existing minimum-simulator-version warnings from bundled native libraries.

`git diff --check` passed after the documentation update.

## Practical validation

### iPad Pro 11-inch simulator, iOS 26.5

- Landscape: Home, Search, Library, and Settings all kept their existing tablet layouts; the dock stayed bottom-center above the home indicator.
- Portrait: Home, Library/Downloaded, and Settings kept usable scroll endpoints above the dock after rotation.
- Home no longer reserves the former top-navigation overlay; its top region is unobscured.
- Search and Library empty states remained clear of the dock.
- A downloaded Library item remained visible and selectable above the dock.
- Settings displayed its final footer/version content above the dock.
- The fourth tab read `Settings`; the avatar remained exposed with the active profile accessibility label and tapping it opened Settings.
- Search and Library header spacing was rechecked after the review follow-up in portrait and landscape. Both headers cleared the status area without the earlier duplicate inset.

### Pixel Tablet emulator, Android 16 / API 36

- Landscape and portrait: root dock remained bottom-center and clear of the gesture indicator.
- Home empty state and the Settings footer remained above the dock.
- Disabling Wi-Fi and data showed the offline state while the floating Retry control stayed at the logical top end.
- Forced RTL mirrored the dock order and placed the compact Retry control at the mirrored logical top end.
- Long-pressing the avatar opened the profile switcher and exposed `Add Profile`.

### iPhone 17 Pro simulator, iOS 26.5

- Existing native phone navigation remained in use.
- The fourth native tab showed the avatar with the visible label `Settings`.

## Focused regression coverage

`MainTabsDestinationTest` now locks these overlay contracts:

- Tablet floating navigation: `top = 0.dp`, `bottom = 64.dp`.
- Native phone tabs: unchanged `bottom = 49.dp`.
- Custom non-classic phone navigation: unchanged `bottom = 72.dp`.
- Classic phone navigation: unchanged zero overlay.
- Tablet Search and Library sticky-header lists receive only the standard 10dp outer top padding; phone paths retain their existing default.
- Existing root/offline presentation assertions continue to cover compact and labeled Retry states.

## Deferred follow-up

- Physical-device checks remain required for at least one iPad and one Android tablet, including touch comfort and real safe-area behavior.
- A live iPad split-view/Stage Manager narrow-width pass was not available through the simulator automation used here. The iPad tablet override remains unchanged, and the focused overlay test verifies that any tablet-classified width uses bottom-only reservation.
- Populated remote catalog/search endpoints were unavailable without an active addon in the disposable validation profiles. Empty states, a saved offline Library item on iPad, final Settings content, and deterministic overlay tests were checked instead.

F05 does not alter F03 or F04 behavior. The branch includes F04 only through the current stable integration base, and the combined state passed the validation above.
