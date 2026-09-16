# F07 network recovery review

Status: implementation complete on `codex/f07-network-recovery`; pull request pending review. Do not merge or release from this note.

- Stable base: `a5d37a02ccd5ff37f5511e2e90ffe40117f4d5ce` (`0.4.12`, build `122`)
- Tested implementation commit: `7a833999`
- Review date: September 16, 2026
- Pull request: pending

## Scope and behavior

F07 adds one profile-aware recovery path for connectivity restoration. It observes the existing network-status contract and runs this order after a confirmed offline-like-to-online transition:

1. Restore or refresh enabled add-on manifests.
2. Resynchronize Home catalog settings.
3. Refresh Home, Search, offline-library metadata, catalog, and detail repositories.

The coordinator exposes `Idle`, `RestoringAddons`, `RefreshingCatalogs`, `Completed`, and `Failed` phases. Reconnect and Retry requests coalesce while the same profile is already recovering. A manual add-on refresh replaces an active same-profile run and forces all enabled manifests through the same ordered path. Profile changes cancel and invalidate the prior generation so late results cannot repopulate the new profile.

Retry actions now enter this central path. Screens no longer maintain independent offline flags, and repositories keep warm content only when it belongs to the current query or target. Home keeps playable local rows visible offline, avoids a blank hero, and separates loading from the absence of an active profile.

## Manifest cache contract

The cache stores each profile independently under `addon_manifest_cache_<profileId>`. Its serialized schema is version `1`. Entries are keyed by manifest URL and contain the raw manifest payload plus a successful-fetch timestamp.

- Freshness window: exactly six hours. Future, zero, malformed, and expired timestamps are stale.
- Stale-while-revalidate: a valid cached manifest hydrates add-on state immediately; stale or missing entries refresh in the background when the app is online.
- Bounds: 32 entries per profile, 512 KiB per entry, 4 MiB final serialized payload, and 4 KiB manifest URLs. Newest entries win when pruning is required.
- Safety: malformed blobs, unsupported versions, invalid manifests, and oversized content are rejected without crashing. Encoding checks the final escaped serialized size and removes oldest entries until it fits.
- Lifecycle: removing an add-on removes its cached manifest; deleting an account or profile removes its profile cache. Cache keys are not shared between profiles.

An individual add-on failure does not block healthy add-ons or the subsequent catalog refresh. A previously valid cached manifest remains usable when a background refresh fails. Explicit refresh may still present the add-on error.

Manifest requests use per-URL single-flight work. Profile and generation checks guard add-on, Home, Search, catalog, and detail publication so cancellation or a late response cannot overwrite a newer request.

## Upstream and donor review

- Official NuvioMobile was reviewed through [`95347544`](https://github.com/NuvioMedia/NuvioMobile/commit/95347544858e31d8cf36569a3b154961276ed46e). Commit [`085e8dc6`](https://github.com/NuvioMedia/NuvioMobile/commit/085e8dc6aaf5072541130be852a782998fc3dbad) contributes delayed loading and error-state presentation, but does not provide the F07 coordinator or cache contract.
- Official draft [PR #1897](https://github.com/NuvioMedia/NuvioMobile/pull/1897), reviewed at head `929757d0e4193eadc2c0b0e9eda19de54b6e88b3`, uses a six-hour cache but was still draft and unvalidated. Its cache was not adopted because it was unversioned and unbounded.
- Official [issue #1612](https://github.com/NuvioMedia/NuvioMobile/issues/1612) documents stale profile, manifest, and Home ordering symptoms; it was closed as not planned.
- `luqmanfadlli/NuvioMobile-Enhanced` at `db904462af18570cf023d69b57c9632efea816f9` and `AKRusso/NuvioMobile-Enhanced` at `ac03c46a` were checked. Neither supplied an equivalent central recovery and cache implementation.

The six-hour freshness interval is retained for compatibility with the official draft's intent. The bounds, schema version, profile lifecycle, single-flight requests, generation guards, and central ordering are fork-specific hardening.

## Automated evidence

The final source passed the complete Android-host suite and Android assembly:

```text
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/muharrem/Library/Android/sdk' \
./gradlew -Pnuvio.ios.distribution=appstore \
  -Pnuvio.android.distribution=playstore \
  :composeApp:testAndroidHostTest :androidApp:assembleDebug \
  --rerun-tasks --console=plain

BUILD SUCCESSFUL in 1m 44s
997 tests, 0 failures, 0 errors, 0 skipped
```

The generated debug APKs were `androidApp-full-debug.apk` and `androidApp-playstore-debug.apk`. Focused cache, coordinator, add-on-state, and Home tests also passed after the final race and request-identity corrections.

Kotlin/Native test compilation passed:

```text
./gradlew -Pnuvio.ios.distribution=full \
  -Pnuvio.android.distribution=full \
  :composeApp:compileTestKotlinIosSimulatorArm64 \
  --rerun-tasks --console=plain

BUILD SUCCESSFUL in 47s
```

The first Xcode attempt correctly failed because the pinned MPVKit submodule was absent. After initializing repository-pinned MPVKit commit `d5cf091c80368bbbc1bbf2d195fbc55d926df888`, the complete unsigned iOS simulator build passed:

```text
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -configuration Debug -sdk iphonesimulator \
  -destination 'id=68A42C7D-B136-4518-A02B-F4CED41E2986' \
  -derivedDataPath /private/tmp/nuvio-f07-derived \
  -disableAutomaticPackageResolution \
  build CODE_SIGNING_ALLOWED=NO

** BUILD SUCCEEDED **
```

## Runtime evidence

Android transition testing used a headless Pixel 8 API 36 emulator and a disposable profile with two local manifest servers. Both manifests were cached while healthy, the second server was then stopped, and the emulator clock was advanced seven hours to make the cache stale.

- A cold launch completed in 1.947 seconds with no fatal exception or ANR.
- Airplane mode plus a background/foreground cycle produced the existing confirmed offline state and kept Retry available without restarting the process.
- Disabling airplane mode plus another background/foreground cycle recovered the healthy add-on while the second add-on remained unavailable.
- The fixture server recorded `GET /manifest.json` before `GET /catalog/movie/f07.json`, confirming manifest-first ordering.
- Home displayed `Recovered Fixture`; Search displayed the fixture; its details page opened with the fixture description.
- Phone and simulated Android tablet layouts rendered recovered Home content.

iPhone and iPad simulators cold-launched the built app and rendered their adaptive no-add-on Home layouts without a crash. They did not execute the full network transition scenario.

A physical Samsung SM-A528B accepted the full debug APK and cold-launched it without a fatal exception or ANR. The device was locked, so no visible interaction or network-transition claim is made.

## Deferred release checks

- Execute the complete offline-to-online and partial-add-on recovery scenario on a physical iPad.
- Repeat the interactive transition on iPhone and iPadOS, including Retry and manual refresh.
- Repeat visible Android interaction on an unlocked physical device.
- Manually recheck Continue Watching, Downloads, and offline playback in a release candidate. Their automated regression coverage passed, but this disposable fixture did not exercise those flows.

These are release gates, not reasons to weaken the ordering or cache contract in review.

## Rollback

F07 can be rolled back by removing the coordinator startup and Retry/manual-refresh routing, reverting the generation hooks in affected repositories, and deleting the manifest-cache platform methods. Stored `addon_manifest_cache_<profileId>` values can be discarded safely; they do not contain user downloads or catalog/download caches. The cache decoder is versioned and already treats unsupported data as empty.
