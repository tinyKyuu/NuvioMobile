# U35 Compose accessibility investigation

Status: dependency compatibility checks passed; prepared for draft PR review. The reported accessibility crash is not locally reproduced or proven fixed.

## Scope and baseline

- Approved base: `5fcf3a11ed0282c905ca1b2b49351ef02335dc11`, PR #11 merge.
- Feature branch: `codex/u35-compose-accessibility`.
- Implementation commit: `c5f408ca5cb65a5ec9307dfbb8457c34645dfa0c`.
- Upstream source: `2a75ad6f373137810e7cd8940478bdfcf1fb9e3a`, `build(deps): align libraries with compose 1.12`.
- Implement U35 only. Keep SQLDelight 2.3.2, full iOS distribution, internal identity, version 0.4.12/build 120, native player, CryptoKit, CJK fonts, downloads, resume and Watch Together behavior.
- Organizer plans and other worktrees are read-only. Native EOF/backward seeking is a separate investigation.

## Existing evidence

Two local simulator crash reports independently show main-thread `EXC_BAD_ACCESS` at `0x8` in `AccessibilityElement.get-node` → `contentOffset` → `bounds` → `CMPAccessibilityElement.accessibilityFrame`. Original reports remain local and unchanged.

[JetBrains PR #3214](https://github.com/JetBrains/compose-multiplatform-core/pull/3214) adds disposed-node guards including `contentOffset`. [Compose 1.12.0](https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.12.0) includes that fix. This is a candidate explanation, not local reproduction or proof that Watch Together caused the crash.

## Diagnosis checkpoint and limits

The unchanged 1.11.1 baseline was built, installed and retained with its dependency graph. The intended regression check would mount real Compose content, retain UIKit accessibility elements across control/sheet removal, and query `accessibilityFrame`. Inspection identified the player's 3500 ms control-hide timer and animated source-panel removal as relevant call sites. No existing XCTest app-host target provides this check. A temporary prototype was drafted under ignored build output but was never built, installed or executed.

The custom reproduction approach stopped after a tooling response failed and the user requested avoiding that approach and continuing. This is an evidence-limited compatibility upgrade. The diagnosing-bugs reproduction/minimization checkpoint was **not completed**. Ordinary accessibility snapshots and successful playback do not substitute for an old-red/new-green regression.

Ranked hypotheses and falsifiers:

1. A removed Compose node remains reachable through UIKit accessibility. Retained frame queries after control or sheet removal should fail on the old version, while queries before removal should not.
2. Whole-controller disposal is required. Removing the controller should distinguish this from control-only removal.
3. Native player or source transitions are required. A minimal Compose screen would stay stable while the same queries on the full player fail.

None is confirmed. The source stack and upstream guard support hypothesis 1 most closely. No accessibility-disabling workaround, production diagnostic code or copied implementation test is included.

Dedicated U35 iPhone 13 mini and iPad Pro 13-inch simulators use iOS 26.5. Existing Watch Together and PR #11 simulators remain untouched. Machine-local IDs, raw logs and build receipts stay under ignored `build/u35-evidence/`.

## Dependency decisions

All upstream U35 hunks are retained unchanged. No adapted or omitted hunk and no compatibility follow-up from another upstream commit was needed. The patch changes two build files, 16 insertions and 7 deletions.

| Resolved iOS dependency | Baseline | Upgrade |
| --- | --- | --- |
| JetBrains Compose runtime/UI/foundation/animation/resources | 1.11.1 | 1.12.0 |
| AndroidX Compose runtime family | 1.11.2 | 1.12.0 |
| Material3 | 1.11.0-alpha07 | 1.12.0-alpha03 |
| Material ripple | 1.11.0-beta03 | 1.12.0 |
| JetBrains Navigation3 UI | 1.1.1 | 1.2.0-alpha02 |
| AndroidX Navigation3 runtime | 1.1.1 | 1.2.0-alpha04 |
| JetBrains Navigation Event Compose | 1.0.1 | 1.1.0 |
| AndroidX Navigation Event | 1.0.2 | 1.1.1 |
| Lifecycle | 2.11.0-beta01 | 2.11.0 |
| JetBrains SavedState bridge/Compose | 1.3.6 | 1.4.0 |
| AndroidX SavedState implementation/Compose | 1.4.0 | 1.4.0 |
| Compottie/core | 2.1.0 | 2.3.1 |
| Keight/core | 0.0.04 | 0.0.07 |
| Skiko | 0.144.6 | 0.150.1 |
| Coroutines core | 1.10.2 | 1.10.2 |
| Serialization JSON/core | 1.10.0 | 1.10.0 |
| Serialization JSON IO | 1.9.0 | 1.9.0 |
| Kotlin stdlib | 2.4.10 | 2.4.10 |
| SQLDelight runtime/native driver | 2.3.2 | 2.3.2 |
| Material icons | 1.7.3 | 1.7.3 |

Compose 1.12's published component set includes these Material3, Navigation3, lifecycle and saved-state versions. Their alignment is supported by the release family; this does not prove that each old companion version was independently incompatible. The explicit ripple dependency selects stable 1.12.0 over Material3's transitive 1.12.0-beta01 request. Saved-state declarations align the JetBrains bridge used by serialized navigation routes and saveable state.

[Compottie 2.3.0](https://github.com/alexzhirkevich/compottie/releases) updates to Compose 1.12; 2.3.1 also corrects even-odd path filling. Its Kotlin requirement remains below the retained 2.4.10 compiler. Coroutines and serialization declarations now match the versions already resolved on baseline iOS. The stale Android-only coroutines 1.8.1 literal is replaced by the shared 1.10.2 dependency.

The icons artifact remains pinned to 1.7.3. Its new plugin warning does not justify an unrelated icon migration. No direct `NativeCanvas`, `NativePaint` or `asFrameworkPaint` use was found; the existing `nativeCanvas` extension compiled with 1.12.

## Automated validation

Baseline source is `5fcf3a11`; upgrade source is `c5f408ca`. Runs use full distribution, Java 17, Xcode 26.6 and iOS Simulator 26.5. Dependency graphs, command logs, fresh XML, counts and app hashes are retained under ignored `build/u35-evidence/`.

| Check | Baseline | Upgrade |
| --- | --- | --- |
| All full Android host tests | 896 passed | 896 passed |
| All Kotlin/Native tests | 898 passed, 1 existing ignored | 898 passed, same ignored test |
| Full Debug simulator app | Passed and installed | Passed, installed on both dedicated simulators |
| Full unsigned generic iOS device Debug build | Not run on baseline | Passed, no archive or physical install |
| Public CryptoKit known-answer self-test | Passed with unchanged bridge | Same source |

The native run uses the real Swift CryptoKit bridge in `scripts/test-ios-player-regressions.sh`, a dedicated U35 simulator and `scripts/phase0_download_test_server.py` on an unused loopback port. With a fixture property and no `--tests` filter, the runner executes all native tests. The ignored test is `DownloadsRequestStorageIosTest.keychain storage round trips and removes request payload`. It is pre-existing and separate from the HTTP downloader coverage.

Representative commands, with machine-local values supplied by the saved receipts:

```sh
env NUVIO_IOS_DISTRIBUTION=full ./gradlew -Pnuvio.android.distribution=full :composeApp:testAndroidHostTest
env NUVIO_TEST_SIMULATOR_ID="$U35_SIMULATOR" scripts/test-ios-player-regressions.sh -Pnuvio.download.test.baseUrl="$U35_FIXTURE_URL"
env NUVIO_IOS_DISTRIBUTION=full ./gradlew :composeApp:dependencies --configuration iosSimulatorArm64CompileKlibraries
env NUVIO_IOS_DISTRIBUTION=full ./gradlew :composeApp:dependencies --configuration iosArm64CompileKlibraries
env NUVIO_IOS_DISTRIBUTION=full xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -sdk iphonesimulator -destination "id=$U35_SIMULATOR" -derivedDataPath build/u35-final-simulator CODE_SIGNING_ALLOWED=NO build
env NUVIO_IOS_DISTRIBUTION=full xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -sdk iphoneos -destination 'generic/platform=iOS' -derivedDataPath build/u35-final-device CODE_SIGNING_ALLOWED=NO build
```

The first host attempt lacked `ANDROID_HOME`; the installed SDK path resolved it. No source change was needed. Native linking emits upstream Swift module-cache debug-information warnings. These do not represent test failures. The simulator build wrapper timed out after 300 seconds; its saved Xcode log subsequently recorded `BUILD SUCCEEDED`. The direct README build command also completed successfully. Final host execution took 2m43s; final native execution took 7m53s, including new native dependency compilation/linking.

Both installed simulator apps match the built launcher, `Nuvio.debug.dylib` and CJK font hashes. Debug Compose code resides in the dylib, so the receipt checks it separately. The public-API symbol check passed against both simulator and device dylibs, and the existing CryptoKit AES-GCM self-test passed. Identity remains `com.tinykyuu.nuvio.internal`, display name `Nuvio`, version 0.4.12/build120. Build receipts include the committed head and hashes; the final review-notes commit changes no production source. No Release/archive build or physical-device runtime test was performed.

## Interactive observations

Baseline iPad: created local `U35 Local` profile through UI, installed a loopback-only synthetic addon, opened catalog/details/source picker, played generated H.264/AAC video, observed controls hide and rotated playback to landscape. The addon confirmation sheet opened and closed. Ordinary accessibility snapshots ran against the app. No matching crash was observed during this limited sequence.

Baseline iPhone mini: launch, continue without account and profile-name text input worked. Pointer scrolling did not respond, so profile creation was not completed there. On the iPad, pointer input also did not reliably reveal hidden player controls. No alternate input injection was used to bypass these failures.

Upgrade iPad: installed over baseline with no erase or uninstall. All four recorded preference/database files were byte-identical immediately after install. After launch, the local profile, addon/catalog and Continue Watching entry remained. The source screen offered resume at 1:59 and generated video advanced past 2:08. Details navigation, the action sheet and native back button worked. The local Library displayed the fixture after adding it through the UI. Search input accepted `U35` and retained it across a Home/Search tab switch. Ordinary accessibility snapshots completed during baseline and upgraded playback, one 44-element player snapshot per version. These were observations, not repeated retained-frame assertions.

Upgrade iPhone mini: installation and launch succeeded with matching artifact hashes. The app retained local mode and showed profile selection. Full player interaction remains unverified on that simulator. No new Nuvio crash report was observed during these limited checks.

These observations do not establish repeated source-panel, full backstack or accessibility-lifetime regression coverage. A loading indicator was observed during baseline navigation; detailed upgraded animation correctness remains unverified. Rotation was observed on baseline playback; upgraded playback ran in that retained landscape orientation.

## Preservation and remaining review

The complete production diff is the two upstream dependency files. SQLDelight/build setup, unofficial identity, signing/server configuration, 0.4.12/build120, public CryptoKit/archive symbol protections, CJK fonts, profile-owned downloads/resume, offline Library, badges/actions/Live Activities, accounts/plugins/debrid and Watch Together source remain unchanged. PR #11's request-owned suspending autoplay handoff and cancellation correction remain intact.

Unrun or incomplete checks remain explicit:

- Actual accessibility regression on old/new dependencies, iPhone and iPad.
- Repeated player control-hide/source-panel transitions with frame queries on both simulator types.
- Full navigation/backstack and saved-state restoration matrix, loading-animation correctness, and persistent real-download upgrade data.
- Physical-device runtime and VoiceOver checks are unavailable in this session and must be reported separately.

Native EOF/backward seeking is not addressed or claimed fixed. Review this as a dependency compatibility change with a plausible accessibility fix, not a confirmed resolution of the reported crash. Rollback is a revert of the U35 dependency commit; there is no data migration.

Stop for organizer/user review. No merge, archive, TestFlight upload, physical-device install, cleanup or next batch.
