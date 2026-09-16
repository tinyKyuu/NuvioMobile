# F02 Offline Library review

## Scope

This change makes completed downloads browsable when Nuvio cannot reach its servers. It persists one metadata snapshot per profile and parent title, caches the artwork needed by that snapshot, and exposes downloaded titles on Home, in a third Library source, and through the normal details route.

The feature started from approved baseline `1392a98ee16e517c2b223cd31d18287719641afc` on `codex/testflight-internal` and addresses the cover, season, and episode information requested in [issue #1262](https://github.com/tinyKyuu/NuvioMobile/issues/1262). The review branch now includes the current integration base `adc32f5c98924d1bd6fdea62ab8f9c05b6cffe74`, including PRs #16 and #17, through merge commit `d2f70db0`.

The `MetaDetails` snapshot conversion shape was adapted from `AKRusso/NuvioMobile-Enhanced` at `ac03c46a159e7e4bbbcd77ad4bdf6ea604b34107`. Nuvio's implementation uses a different ownership and storage model: snapshots belong to one profile/title record, while download records remain the source of truth for playable files.

## Behavior

- Completed movie variants and series episodes reconcile into one title record per profile, media type, and downloaded parent ID.
- Provider IDs and the downloaded parent identity are stored separately. Offline navigation always uses the downloaded parent identity.
- Metadata captured during normal details browsing is saved immediately. Missing or stale records refresh through preferred add-ons with conditional requests and TMDB fallback.
- Successful metadata is fresh for 24 hours. Automatic failures retry with exponential backoff from 30 seconds to 30 minutes. A run starts at most eight metadata refreshes and eight artwork refreshes, excludes work already in flight, and permits two network requests at once.
- Metadata responses are capped at 4 MiB. Artwork responses are capped at 8 MiB and must have an image content type when supplied plus a supported file signature before an atomic local save.
- Poster, background, logo, season posters, and thumbnails for downloaded episodes are retained in app storage. Unreferenced artwork is deleted only after checking all profile records.
- Refresh results are rejected after a profile switch, download-set generation change, or title deletion. Deleting a profile removes its downloads and offline title records after a successful remote deletion, or immediately for a local anonymous profile.
- Home shows a Downloaded row and limits Continue Watching to playable local episodes while offline. Library includes Saved, Downloaded, and Cloud sources.
- Downloads open the normal movie or series details route, so the same snapshot and playback checks apply from every entry point.

## Organizer review fixes

- Offline-like details now prefer the persisted offline snapshot over repository or memory-cached online metadata. Opening that snapshot suppresses the ordinary metadata load, settings-driven enrichment reloads, ratings, comments, trailers, backdrop enrichment, and remote episode-progress refresh.
- Both series episode layouts use the same offline eligibility rule. Downloaded episodes remain actionable; other episode cards are disabled and labeled `Internet required`.
- Metadata freshness is based on the last successful refresh. After an automatic failure, complete but stale metadata becomes eligible exactly at its retry boundary instead of waiting another 24 hours.
- Artwork has independent attempt, failure, and retry state. Partial downloads retain valid existing assets, retry missing assets after backoff without requiring another metadata fetch, and clear failure state when complete or when the download reference set changes.
- iOS artwork replacement writes a unique same-directory temporary file and commits with POSIX `rename`, preserving the last good target when replacement fails and cleaning the temporary file on every path.

## Validation

| Check | Result | Local evidence |
|---|---:|---|
| SQLDelight migration verification | Passed | `build/f02-evidence/review-fixes-migration-final/gradle.log` |
| Final Android host suite | 934 tests, 0 failed, 0 errors, 0 skipped | `build/f02-evidence/review-fixes-android-host-final/gradle.log` |
| Offline details policy on Android host | 2 tests, 0 failed | `composeApp/build/test-results/testAndroidHostTest/TEST-com.nuvio.app.features.details.MetaDetailsOfflinePolicyTest.xml` |
| Offline library logic on Android host | 18 tests, 0 failed | `composeApp/build/test-results/testAndroidHostTest/TEST-com.nuvio.app.features.downloads.OfflineLibraryLogicTest.xml` |
| Android full debug APK | Built successfully | `build/f02-evidence/review-fixes-android-app-final/gradle.log` |
| iOS simulator Kotlin compile | Passed | `build/f02-evidence/review-fixes-ios-compile/gradle.log` |
| Full-distribution iOS downloads suite | 73 tests, 0 failed, 0 errors, 1 expected keychain skip | `build/f02-evidence/review-fixes-ios-downloads-final/gradle.log` |
| iOS offline artwork replacement | 1 native iOS test, 0 failed | `composeApp/build/test-results/iosSimulatorArm64Test/TEST-com.nuvio.app.features.downloads.OfflineArtworkPlatformIosTest.xml` |
| Full iOS simulator app | Built successfully | `build/f02-evidence/review-fixes-ios-simulator-build-final/xcodebuild.log` |
| Populated dedicated iOS simulator runtime | Cold offline-like launch, local artwork and metadata, downloaded-only episode eligibility, and local MP4 playback verified | `build/f02-evidence/review-fixes-ios-simulator-runtime/` |
| Unsigned arm64 iOS device IPA | Built and archive-tested successfully | `build/f02-evidence/review-fixes-ios-device-build-final/build-ios-ipa.log` |

The Android suite initially exposed a timing assumption in `WatchedItemsStoreTest`: a concurrent reader can run before the first provider dirty-set write. The assertion now treats that absent set as empty, matching the store's valid initial state; the isolated test and the final 934-test suite pass.

The full-distribution iOS suite uses the Swift crypto bridge harness from the current integration base and now links and passes. The one skip is the existing keychain round-trip test in `DownloadsRequestStorageIosTest`.

Runtime verification used the dedicated `Nuvio F02 Offline Library` iPhone 17 Pro simulator on iOS 26.5. A real anonymous session was created through the app, then the fixture wrote an app-native profile payload for `Offline Reviewer`, one completed 40-second H.264/AAC episode, its series snapshot, and four local artwork files. The dedicated simulator's server endpoint was set to unreachable loopback `http://127.0.0.1:9`, which produced the app's `ServersUnreachable` offline-like state without altering the host or other simulators. A cold launch showed the Downloaded row and local poster; details rendered the persisted series data; episode 1 was actionable as `Downloaded`; episode 2 was disabled as `Internet required`; and episode 1 played from its local file. Evidence includes `cold-launch-offline-library.png`, `offline-details-episode-policy.png`, `local-playback-from-downloaded-episode.png`, `seed.log`, and `runtime-filtered.log` in the runtime evidence directory. The simulator was shut down afterward.

The iOS replacement success path is exercised against the real filesystem. The failed-commit path is tested through the shared commit contract: the previous target remains, unique temporary names differ, and cleanup always runs. The test suite does not fault-inject a failing POSIX `rename` inside the simulator process.

No physical device installation or testing was performed, as requested.

## Artifacts

| Artifact | Size | SHA-256 |
|---|---:|---|
| `androidApp-full-debug.apk` | 158,764,897 bytes | `71b596e1a099884bcc0f12c8a44dfc7f6463518e137feae9bab2b4bae3bfe46d` |
| `nuvio-0.4.12-full-debug.ipa` | 83,157,891 bytes | `c872a329126c2e47d0568e302f95c886f17b5751c093a42b0d8c49ea1a0730f4` |

## Rollback

Revert the F02 feature and UI commits. Migration 1 only adds `offline_title_record` and its index, so existing download rows remain intact. If local cleanup is required, delete the offline title rows and the `NuvioOfflineLibrary/Artwork` cache; no downgrade migration is needed for existing download data.
