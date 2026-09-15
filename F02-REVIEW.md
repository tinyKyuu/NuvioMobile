# F02 Offline Library review

## Scope

This change makes completed downloads browsable when Nuvio has no network connection. It persists one metadata snapshot per profile and parent title, caches the artwork needed by that snapshot, and exposes downloaded titles on Home, in a third Library source, and through the normal details route.

The work started from approved baseline `1392a98ee16e517c2b223cd31d18287719641afc` on `codex/testflight-internal` and addresses the cover, season, and episode information requested in [issue #1262](https://github.com/tinyKyuu/NuvioMobile/issues/1262).

The `MetaDetails` snapshot conversion shape was adapted from `AKRusso/NuvioMobile-Enhanced` at `ac03c46a159e7e4bbbcd77ad4bdf6ea604b34107`. Nuvio's implementation uses a different ownership and storage model: snapshots belong to one profile/title record, while download records remain the source of truth for playable files.

## Behavior

- Completed movie variants and series episodes reconcile into one title record per profile, media type, and downloaded parent ID.
- Provider IDs and the downloaded parent identity are stored separately. Offline navigation always uses the downloaded parent identity.
- Metadata captured during normal details browsing is saved immediately. Missing or stale records refresh through preferred add-ons with conditional requests and TMDB fallback.
- Successful metadata is fresh for 24 hours. Automatic failures retry with exponential backoff from 30 seconds to 30 minutes. A run starts at most eight metadata refreshes and permits two metadata or artwork requests at once; manual refresh bypasses freshness and backoff.
- Metadata responses are capped at 4 MiB. Artwork responses are capped at 8 MiB and must have an image content type when supplied plus a supported file signature before an atomic local save.
- Poster, background, logo, season posters, and thumbnails for downloaded episodes are retained in app storage. Unreferenced artwork is deleted only after checking all profile records.
- Refresh results are rejected after a profile switch, download-set generation change, or title deletion. Deleting a profile removes its downloads and offline title records after a successful remote deletion, or immediately for a local anonymous profile.
- Home shows a Downloaded row and limits Continue Watching to playable local episodes while offline. Library includes Saved, Downloaded, and Cloud sources. Offline series details disable episodes without a completed local download and label them `Internet required`.
- Downloads open the normal movie or series details route, so the same snapshot and playback checks apply from every entry point.

## Validation

| Check | Result | Local evidence |
|---|---:|---|
| SQLDelight migration verification | Passed | `build/f02-evidence/migration/gradle.log` |
| Native HTTP fixture tests | 8 passed | `build/f02-evidence/http-fixture-unit.log` |
| Baseline regression at `1392a98e` | Failed as expected because the F02 APIs do not exist | `build/f02-evidence/baseline/gradle.log` |
| Final Android host suite | 919 passed, 0 failed | `build/f02-evidence/android-host-final/gradle.log` |
| Offline library logic on Android host | 15 passed | `composeApp/build/test-results/testAndroidHostTest/TEST-com.nuvio.app.features.downloads.OfflineLibraryLogicTest.xml` |
| Home behavior tests | 33 passed | `composeApp/build/test-results/testAndroidHostTest/TEST-com.nuvio.app.features.home.HomeScreenTest.xml` |
| Android full debug APK | Built successfully | `build/f02-evidence/android-app-build-final/gradle.log` |
| iOS simulator Kotlin compile | Passed | `build/f02-evidence/ios-compile-post-review/gradle.log` |
| iOS appstore-source-set downloads suite | 69 passed, 0 failed, 1 keychain test skipped | `build/f02-evidence/ios-native-downloads-appstore/gradle.log` |
| Full iOS simulator app | Built successfully | `build/f02-evidence/ios-simulator-build-post-review/xcodebuild.log` |
| Dedicated iOS simulator runtime | Launch, local profile Home, all three Library sources, and Downloaded empty state verified | `build/f02-evidence/ios-simulator-runtime/` |
| Unsigned arm64 iOS device IPA | Built and archive-tested successfully | `build/f02-evidence/ios-device-build-final/build-ios-ipa.log` |

The standalone full-distribution Kotlin/Native test executable cannot link the app's Swift AES bridge symbols (`nuvio_aes_gcm_encrypt` and `nuvio_aes_gcm_decrypt`). This is an existing test-target boundary rather than an F02 compile error. The same native downloads suite passes under the appstore source set, and both full-distribution iOS app targets link successfully. The failed linker evidence is retained at `build/f02-evidence/ios-native-downloads/gradle.log`.

Runtime verification used the dedicated `Nuvio F02 Offline Library` iPhone 17 Pro simulator. It was shut down after the run. A hand-seeded profile payload was rejected by the app's profile integrity guard, so the populated Downloaded card was not bypassed into the UI; grouping, identity, episode retention, deletion, and playability are covered by the common tests. No physical device installation was performed.

## Artifacts

| Artifact | Size | SHA-256 |
|---|---:|---|
| `androidApp-full-debug.apk` | 158,277,512 bytes | `6737e295d02ab76c3c05c631cdb0b430c0a5a5750da9b9ae1aa699d5a69abbf2` |
| `nuvio-0.4.12-full-debug.ipa` | 83,108,164 bytes | `6a4d081a50fa20034f97048b5378c86e548ba3893f45070cc4aa0934b98eb096` |

## Rollback

Revert the F02 feature and UI commits. Migration 1 only adds `offline_title_record` and its index, so existing download rows remain intact. If local cleanup is required, delete the offline title rows and the `NuvioOfflineLibrary/Artwork` cache; no downgrade migration is needed for existing download data.
