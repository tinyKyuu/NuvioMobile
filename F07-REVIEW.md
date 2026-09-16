# F07 network recovery review

Status: implementation complete on `codex/f07-network-recovery`; [PR #26](https://github.com/tinyKyuu/NuvioMobile/pull/26) is pending review. Do not merge or release from this note.

- Stable base: `a5d37a02ccd5ff37f5511e2e90ffe40117f4d5ce` (`0.4.12`, build `122`)
- Tested implementation head: `c387f41cf1b8bb05d8985281f22f817111556fbc`
- Review date: September 17, 2026
- Pull request: [#26](https://github.com/tinyKyuu/NuvioMobile/pull/26)

## Scope and behavior

F07 adds one profile-aware recovery path for connectivity restoration. It observes the existing network-status contract and runs this order after a confirmed offline-like-to-online transition:

1. Restore or refresh enabled add-on manifests.
2. Resynchronize Home catalog settings.
3. Refresh Home, Search, offline-library metadata, catalog, and detail repositories.

The coordinator exposes `Idle`, `RestoringAddons`, `RefreshingCatalogs`, `Completed`, and `Failed` phases. Each successful manifest starts a partial catalog reconciliation. One final reconciliation runs after every manifest attempt settles, including when fresh cached manifests need no refresh.

Home allows up to four catalog requests at once and publishes each result when it completes. A completed healthy catalog no longer waits for another request in its batch. Partial passes merge recovered providers without pruning other providers' valid warm rows. Transient manifest loading and error flags do not invalidate catalog cache keys. Final refreshes retain those rows if their catalog request hangs or fails. Home catalog settings still synchronize against the complete enabled add-on list.

Search and Discover receive the complete enabled add-on list plus a separate set of manifests ready for a partial fetch. Search merges each completed provider's results into the current query's retained sections. Pending or failed providers keep their warm sections until valid replacement data arrives. Discover keeps its selected provider, genre, and warm items while a different provider recovers. A temporarily missing manifest does not remove that provider's cached sources. Partial passes do not fetch the selected provider until it is ready, and the final pass reconciles the full provider set. Empty-state decisions include providers whose manifests are still pending. Removing a provider or changing the query still discards unrelated content.

Retry and manual refresh wait for a new connectivity probe to report Online. An older Online value cannot start recovery. Duplicate Online notifications and repeated Retry requests coalesce while an uninterrupted same-profile recovery is active. A new confirmed offline-like-to-online cycle replaces that attempt with a fresh generation. Late success and failure from the superseded attempt cannot publish recovery state or start catalog reconciliation. A manual add-on refresh also replaces the active run and forces all enabled manifests through the ordered path. Profile changes cancel and invalidate the prior generation.

Retry actions now enter this central path. Screens no longer maintain independent offline flags, and repositories keep warm content only when it belongs to the current query or target. Starting a different Search query clears the previous query's rows; a same-request recovery may retain them. Home keeps playable local rows visible offline, avoids a blank hero, and separates loading from the absence of an active profile.

## Manifest cache contract

The cache stores each profile independently under `addon_manifest_cache_<profileId>`. Its serialized schema is version `1`. Entries are keyed by manifest URL and contain the raw manifest payload plus a successful-fetch timestamp.

- Freshness window: exactly six hours. Future, zero, malformed, and expired timestamps are stale.
- Stale-while-revalidate: a valid cached manifest hydrates add-on state immediately; stale or missing entries refresh in the background when the app is online.
- Bounds: 32 entries per profile, 512 KiB per entry, 4 MiB final serialized payload, and 4 KiB manifest URLs. Newest entries win when pruning is required.
- Safety: malformed blobs, unsupported versions, invalid manifests, and oversized content are rejected without crashing. Encoding checks the final escaped serialized size and removes oldest entries until it fits.
- Lifecycle: removing an add-on removes its cached manifest; deleting an account or profile removes its profile cache. Cache keys are not shared between profiles.

An individual add-on failure does not block healthy add-ons or their catalog refresh. A previously valid cached manifest remains usable when a background refresh fails. Explicit refresh may still present the add-on error.

Manifest requests use per-URL single-flight work. A reconnect generation replaces a background request that began while the app was offline, while duplicate work in the same recovery generation joins the active request. Add-on, Home, Search, catalog, and detail repositories check profile or request generations before publishing results.

Manifest-cache ownership, mutation, serialization, and persistence share a lock. Every completion carries its captured profile ID and generation, rechecks that ownership under the lock, and persists under the captured profile. Profile changes use the same lock. Parallel successful completions retain both entries; a completion from a discarded profile cannot mutate or persist the new profile's cache.

## Upstream and donor review

- Official NuvioMobile was reviewed through [`95347544`](https://github.com/NuvioMedia/NuvioMobile/commit/95347544858e31d8cf36569a3b154961276ed46e). Commit [`085e8dc6`](https://github.com/NuvioMedia/NuvioMobile/commit/085e8dc6aaf5072541130be852a782998fc3dbad) contributes delayed loading and error-state presentation, but does not provide the F07 coordinator or cache contract.
- Official draft [PR #1897](https://github.com/NuvioMedia/NuvioMobile/pull/1897), reviewed at head `929757d0e4193eadc2c0b0e9eda19de54b6e88b3`, uses a six-hour cache but was still draft and unvalidated. Its cache was not adopted because it was unversioned and unbounded.
- Official [issue #1612](https://github.com/NuvioMedia/NuvioMobile/issues/1612) documents stale profile, manifest, and Home ordering symptoms; it was closed as not planned.
- `luqmanfadlli/NuvioMobile-Enhanced` at `db904462af18570cf023d69b57c9632efea816f9` and `AKRusso/NuvioMobile-Enhanced` at `ac03c46a` were checked. Neither supplied an equivalent central recovery and cache implementation.

The six-hour freshness interval is retained for compatibility with the official draft's intent. The bounds, schema version, profile lifecycle, single-flight requests, generation guards, and central ordering are fork-specific hardening.

## Automated evidence

The final source passed the complete Android-host suite and the full Android debug build:

```text
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/muharrem/Library/Android/sdk' \
./gradlew -Pnuvio.ios.distribution=appstore \
  -Pnuvio.android.distribution=full \
  :composeApp:testAndroidHostTest :androidApp:assembleFullDebug \
  --rerun-tasks --console=plain

BUILD SUCCESSFUL in 1m 36s
1,020 tests, 0 failures, 0 errors, 0 skipped
```

The Play Store variant passed in its required separate distribution invocation:

```text
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/muharrem/Library/Android/sdk' \
./gradlew -Pnuvio.ios.distribution=appstore \
  -Pnuvio.android.distribution=playstore \
  :androidApp:assemblePlaystoreDebug \
  --rerun-tasks --console=plain

BUILD SUCCESSFUL in 1m 22s
```

The generated APKs were `androidApp-full-debug.apk` and `androidApp-playstore-debug.apk`. Focused Home, Search, Discover, cache, coordinator, and manifest-recovery tests passed with this command:

```text
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/muharrem/Library/Android/sdk' \
./gradlew -Pnuvio.ios.distribution=appstore \
  -Pnuvio.android.distribution=playstore \
  :composeApp:testAndroidHostTest \
  --tests 'com.nuvio.app.core.network.NetworkRecovery*Test' \
  --tests 'com.nuvio.app.features.addons.ManifestRecoveryTest' \
  --tests 'com.nuvio.app.features.addons.AddonManifestCache*Test' \
  --tests 'com.nuvio.app.features.search.SearchRequestStateTest' \
  --console=plain

BUILD SUCCESSFUL in 48s
41 tests, 0 failures, 0 errors, 0 skipped
```

The focused regressions exercise these boundaries:

- Fresh manifest cache entries produce no manifest attempts. The production Home loader publishes a healthy row while another catalog remains suspended. The earlier partial-manifest and final-reconciliation test remains in the suite.
- A seeded warm row for a pending provider remains visible during a partial refresh, a suspended final catalog refresh, and that request's eventual failure. Test definitions use the production descriptor signature.
- The production recovery controller ignores stale Online after Retry, receives confirmed NoInternet, then completes a recovery generation after Online. A separate case verifies genuine duplicate Retry coalescing.
- The production controller replaces a held run after a second confirmed outage. Tests release both late success and late failure from the first run, check that duplicate Online and repeated Retry still coalesce, and verify that only the newest generation publishes after rapid cycles and a profile switch.
- The production recovery controller and Search repository controller run multi-provider recovery together. Search publishes A's fresh results while retaining B's warm results; Discover retains selected B while B's manifest hangs and its final catalog request hangs or fails. Separate cases cover a stale cached B manifest and a temporarily missing B manifest, then verify valid replacement data, provider removal, and query changes.
- Pending-manifest tests use a recovered provider with no compatible catalogs and a second provider whose manifest is missing. Neither Search nor Discover shows a false no-addon or no-catalog state. Final success publishes B's content; final failure resolves to RequestFailed. The changed-query test now drives a held request through the production repository controller.
- Known catalogs deferred by a partial pass remain loading until final reconciliation, even when no manifest is pending. Removing an enabled provider with a temporarily missing manifest clears its retained query results.
- The production manifest-cache store serializes simultaneous completions and a profile switch during persistence. Tests decode persisted blobs to verify both successful entries and profile isolation, and reject a completion released after its profile was replaced.

Home tests inject catalog definitions and loaders to avoid platform resources and HTTP. Search and Discover tests inject catalog loaders and selection storage into the controller used by the production repository. Cache-store tests inject storage; they do not exercise platform preferences. Other unit coverage verifies empty-cache manifest selection, replacement of a suspended background request by reconnect recovery, and profile invalidation. This is not a complete cold offline app-launch and reconnect test.

Kotlin/Native test compilation passed:

```text
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/muharrem/Library/Android/sdk' \
NUVIO_ENGINE_ROOT='/Users/muharrem/Documents/ChatGPT/Nuvio iOS/build/nuvio-engine' \
./gradlew -Pnuvio.ios.distribution=full \
  -Pnuvio.android.distribution=full \
  :composeApp:compileTestKotlinIosSimulatorArm64 \
  --rerun-tasks --console=plain

BUILD SUCCESSFUL in 1m 11s
```

The complete unsigned iOS simulator build used repository-pinned MPVKit commit `d5cf091c80368bbbc1bbf2d195fbc55d926df888`:

```text
NUVIO_IOS_DISTRIBUTION=full \
NUVIO_ENGINE_ROOT='/Users/muharrem/Documents/ChatGPT/Nuvio iOS/build/nuvio-engine' \
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -configuration Debug -sdk iphonesimulator \
  -destination 'id=68A42C7D-B136-4518-A02B-F4CED41E2986' \
  -derivedDataPath /private/tmp/nuvio-f07-final-derived \
  -disableAutomaticPackageResolution \
  build CODE_SIGNING_ALLOWED=NO

** BUILD SUCCEEDED **
```

## Runtime evidence

The initial F07 runtime pass used a headless Pixel 8 API 36 emulator and a disposable profile with two local manifest servers. These Android transition checks predate both rounds of follow-up fixes. Both manifests were cached while healthy, the second server was then stopped, and the emulator clock was advanced seven hours to make the cache stale.

- A cold launch completed in 1.947 seconds with no fatal exception or ANR.
- Airplane mode plus a background/foreground cycle produced the existing confirmed offline state and kept Retry available without restarting the process.
- Disabling airplane mode plus another background/foreground cycle recovered the healthy add-on while the second add-on remained unavailable.
- The fixture server recorded `GET /manifest.json` before `GET /catalog/movie/f07.json`, confirming manifest-first ordering.
- Home displayed `Recovered Fixture`; Search displayed the fixture; its details page opened with the fixture description.
- Phone and simulated Android tablet layouts rendered recovered Home content.

The September 17 rebuilt app cold-launched on the iPhone simulator and rendered its no-add-on Home state without a crash. The initial pass also checked the iPad simulator layout. Interactive iOS offline-to-online transitions were not performed in this follow-up.

The launch check used device `68A42C7D-B136-4518-A02B-F4CED41E2986`, installed `/private/tmp/nuvio-f07-final-derived/Build/Products/Debug-iphonesimulator/Nuvio.app`, and ran `xcrun simctl launch --terminate-running-process 68A42C7D-B136-4518-A02B-F4CED41E2986 com.tinykyuu.nuvio.internal`. The simulator was shut down after visual verification.

During the initial F07 pass, a physical Samsung SM-A528B accepted the full debug APK and cold-launched it without a fatal exception or ANR. The device was locked, so no visible interaction or network-transition claim is made.

## Deferred release checks

- Physical-device Wi-Fi transitions remain deferred. Execute the complete offline-to-online and partial-add-on recovery scenario on a physical iPad.
- Run the interactive transition on iPhone and iPadOS, including Retry and manual refresh.
- Repeat visible Android interaction on an unlocked physical device.
- Manually recheck Continue Watching, Downloads, and offline playback in a release candidate. Their automated regression coverage passed, but this disposable fixture did not exercise those flows.

These are release gates, not reasons to weaken the ordering or cache contract in review.

## Rollback

F07 can be rolled back by removing the coordinator startup and Retry/manual-refresh routing, reverting the generation hooks in affected repositories, and deleting the manifest-cache platform methods. Stored `addon_manifest_cache_<profileId>` values can be discarded safely; they do not contain user downloads or catalog/download caches. The cache decoder is versioned and already treats unsupported data as empty.
