# F07 network recovery review

Status: implementation complete on `codex/f07-network-recovery`; [PR #26](https://github.com/tinyKyuu/NuvioMobile/pull/26) is pending review. Do not merge or release from this note.

- Stable base: `a5d37a02ccd5ff37f5511e2e90ffe40117f4d5ce` (`0.4.12`, build `122`)
- Tested implementation head: `7ecc0806a58e5d5b2de5caa503e97c187f09046f`
- Review date: September 17, 2026
- Pull request: [#26](https://github.com/tinyKyuu/NuvioMobile/pull/26)

## Scope and behavior

F07 adds one profile-aware recovery path for connectivity restoration. It observes the existing network-status contract and runs this order after a confirmed offline-like-to-online transition:

1. Restore or refresh enabled add-on manifests.
2. Resynchronize Home catalog settings.
3. Refresh Home, Search, offline-library metadata, catalog, and detail repositories.

The coordinator exposes `Idle`, `RestoringAddons`, `RefreshingCatalogs`, `Completed`, and `Failed` phases. Each successful manifest starts a partial catalog reconciliation. One final reconciliation runs after every manifest attempt settles, including when fresh cached manifests need no refresh.

When only some manifests need recovery, the shared AddonRepository batch operation admits usable fresh cached manifests immediately. Their Home, Search, and selected Discover catalogs can refresh while an unrelated missing or stale manifest is still held. Fresh manifests are not fetched again just to trigger catalog readiness. Manual refresh still forces every enabled manifest through transport; an all-fresh reconnect needs no manifest transport and proceeds to final reconciliation.

Home allows up to four catalog requests at once and publishes each result when it completes. A completed healthy catalog no longer waits for another request in its batch. Partial passes merge recovered providers without pruning other providers' valid warm rows. Transient manifest loading and error flags do not invalidate catalog cache keys. Final refreshes retain those rows if their catalog request hangs or fails. Home catalog settings still synchronize against the complete enabled add-on list.

Search and Discover receive the complete enabled add-on list plus a separate set of manifests ready for a partial fetch. Search merges each completed provider's results into the current query's retained sections. Pending or failed providers keep their warm sections until valid replacement data arrives. Discover keeps its selected provider, genre, and warm items while a different provider recovers. A temporarily missing manifest does not remove that provider's cached sources. Partial passes do not fetch the selected provider until it is ready, and the final pass reconciles the full provider set. Empty-state decisions include providers whose manifests are still pending. Removing a provider or changing the query still discards unrelated content.

A successfully parsed empty Search page removes that provider/catalog's old section. It does not behave like a failed request. Other providers keep their sections, and genuine transport failures retain valid warm results. All-empty successful responses produce NoResults. If nothing can be displayed and another catalog or manifest failed, the state reports RequestFailed instead of claiming there were no matches.

Details retain a one-time recovery retry for a request already loading when reconciliation completes. A late success satisfies recovery without another fetch. A late failure triggers one fresh attempt, and a failed follow-up stops rather than looping. Recovery callbacks run on the repository's UI scope and carry the profile and interrupted-request identity. New post-recovery loads and newer titles/profiles do not inherit an old request's retry. Warm metadata and its existing snapshot remain available if refresh fails.

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
1,042 tests, 0 failures, 0 errors, 0 skipped
```

The Play Store variant passed in its required separate distribution invocation:

```text
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/muharrem/Library/Android/sdk' \
./gradlew -Pnuvio.ios.distribution=appstore \
  -Pnuvio.android.distribution=playstore \
  :androidApp:assemblePlaystoreDebug \
  --rerun-tasks --console=plain

BUILD SUCCESSFUL in 1m 20s
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

BUILD SUCCESSFUL in 52s
63 tests, 0 failures, 0 errors, 0 skipped
```

The first production-boundary run reproduced the review findings before their fixes: 9 of 16 tests failed and 7 controls passed. The failing cases covered fresh-provider blocking, warm Search rows surviving empty successes, and delayed Details failures consuming recovery. The completed acceptance matrix is:

| Case | Production path and assertion |
| --- | --- |
| All fresh; fresh A with missing or stale B; manual all-provider refresh | `recoverAddonManifestBatch`, used by AddonRepository, performs selection, initial readiness, and result collection. Home/Search/selected-ready Discover publish A while B remains held, without an A manifest request. All-fresh skips manifest transport. Manual refresh fetches both and publishes the first success before the second settles. |
| Warm/cold Home and Search; ready/pending Discover selection | Repository/controller tests cover independent catalog publication, warm rows through partial and failed final refreshes, temporarily missing manifests, preserved selected B, and successful final replacement. Changing the query or removing a provider clears unrelated cached state. |
| Search nonempty, empty, failed, and delayed responses | The production parser and `sectionFromPage` conversion feed the real Search controller. Empty A removes only A while B fails or remains delayed; successful B later replaces its warm row. All-empty produces NoResults. Cold mixed empty/failure, including an unavailable manifest, reports RequestFailed. |
| Details failed before recovery, already loading, or opened during recovery | The real Details controller retries a held pre-recovery failure once, accepts late success without a duplicate, handles a screen opened during central recovery, and stops after a failed follow-up. Recovery during asynchronous error presentation is not lost. |
| Details ownership and retained content | Title/profile changes reject late success and failure. A queued recovery callback cannot attach itself to a newer load. Post-recovery loads do not receive a duplicate retry. Failed forced refreshes retain warm metadata and do not overwrite the captured snapshot. |
| Second outages, Retry, manual refresh, and profile changes | Controller tests assert fresh-probe gating, same-run duplicate coalescing, replacement generations on a new outage or confirmed manual refresh, force-all propagation, and rejection of superseded publications after rapid cycles or profile switches. |
| Manifest cache and background work | Existing tests cover bounded/expired/empty caches, replacing a background manifest request on reconnect, simultaneous successful persistence, and profile switches before completion and during persistence. Persisted test blobs are decoded and checked. |

Home tests inject catalog definitions and loaders. Search tests inject pages or loaders, including real catalog JSON parsing and empty-result conversion. Details tests inject the metadata loader, localized failure message, and snapshot capture callback while exercising the controller used by the production singleton. These callback assertions do not verify platform snapshot persistence. Cache-store tests inject storage, not platform preferences. The full suite also retains offline-library, Downloads, and Continue Watching coverage. Automated tests alone do not constitute a complete cold offline app-launch or physical-network test.

Kotlin/Native test compilation passed:

```text
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/muharrem/Library/Android/sdk' \
NUVIO_ENGINE_ROOT='/Users/muharrem/Documents/ChatGPT/Nuvio iOS/build/nuvio-engine' \
./gradlew -Pnuvio.ios.distribution=full \
  -Pnuvio.android.distribution=full \
  :composeApp:compileTestKotlinIosSimulatorArm64 \
  --rerun-tasks --console=plain

BUILD SUCCESSFUL in 51s
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

Build logs are `/private/tmp/f07-round3-android-final.log`, `/private/tmp/f07-round3-playstore-final.log`, `/private/tmp/f07-round3-focused-final.log`, `/private/tmp/f07-round3-native-final.log`, and `/private/tmp/f07-round3-xcode-final.log`. The meaningful failing run is `/private/tmp/f07-round3-red.log`.

## Runtime evidence

### Final implementation

The September 17 controlled Android checks installed the full debug APK built from `7ecc0806` into a read-only Pixel 8 API 36 emulator session. Two loopback-only HTTP servers supplied disposable add-ons A and B. Temporary emulator-only TCP 443 rejection rules blocked the public connectivity probes while leaving the fixture endpoints reachable. The offline-to-online scenarios required logged NoInternet and Online probe results, not just a UI gesture or an assumed network change.

- Fresh A with missing B: the app cold-started with A's fresh manifest cached, no B manifest, and no successful A catalog response. After a confirmed reconnect, Home and selected Discover rendered `F07 A Recovered` while B's manifest request remained held. Server events contained B's held manifest request and A's catalog request, with no A manifest fetch. Releasing B as a failure allowed final reconciliation to complete.
- Interrupted Details: Android log timestamps show A's metadata request beginning at 01:41:37 and remaining held through confirmed NoInternet, Online, and recovery generation 3 completion at 01:42:09.493. The repository logged its pending retry at 01:42:09.525. Only then did the fixture release the old request as HTTP 503. The app logged one automatic replacement at 01:42:09.851 and rendered `Verified A metadata Recovered`. No Retry tap or navigation was needed after the late failure.
- Empty Search responses: the query `fixture` first displayed both A and B. Manual add-on refresh requested fresh Online probes and completed recovery generations 4 and 5. A's HTTP 200 `{"metas":[]}` removed only A while B's nonempty result remained visible. After B also returned an empty success, the same query displayed `No results found` with neither old row. Server events, repository recovery logs, and screenshots agree on both outcomes. Failed-provider retention and delayed nonempty replacement have regression coverage, not additional live-fixture claims.

The mixed-cache artifacts are `/private/tmp/f07-round3-runtime-mixed.log`, `f07-round3-mixed-events.json`, `f07-round3-mixed-logcat.log`, and the `f07-round3-mixed-home-held.png` and `f07-round3-mixed-discover-held.png` screenshots. The Details artifacts are `/private/tmp/f07-round3-runtime-details.log`, `f07-round3-details-events.json`, `f07-round3-details-logcat.log`, and `f07-round3-details-recovered.png`. Search artifacts are `/private/tmp/f07-round3-runtime-empty-manual.log`, `f07-round3-search-events.json`, and the logs, XML, and screenshots named `f07-round3-search-mixed-empty` and `f07-round3-search-all-empty` in the same directory.

Earlier attempts with a fixture control-connection reset or no confirmed foreground probe within the test's 45-second deadline are excluded from acceptance evidence. One offline probe arrived after that deadline. The accepted Search check used explicit manual refresh; it is not evidence for a Search-screen foreground-triggered reconnect.

The fixture server and driver are `/private/tmp/f07-round3-server.cjs` and `/private/tmp/f07-round3-runtime.cjs`. Both IPv4 and IPv6 test rejection rules were verified absent before stopping the read-only emulator. The fixture servers were stopped. No physical device was changed in this follow-up.

The same final source built, installed, and cold-launched on iPhone simulator `68A42C7D-B136-4518-A02B-F4CED41E2986`. Visual inspection confirmed the expected no-active-addons Home screen. The app was `/private/tmp/nuvio-f07-final-derived/Build/Products/Debug-iphonesimulator/Nuvio.app`, launched with `xcrun simctl launch --terminate-running-process 68A42C7D-B136-4518-A02B-F4CED41E2986 com.tinykyuu.nuvio.internal`. The screenshot is `/private/tmp/f07-round3-ios-launch.png`. The simulator was shut down after verification. Interactive iOS offline-to-online transitions were not performed in this follow-up.

### Earlier baseline checks

The initial F07 runtime pass used a headless Pixel 8 API 36 emulator and a disposable profile with two local manifest servers. These Android transition checks predate the follow-up fixes and are historical evidence only. Both manifests were cached while healthy, the second server was then stopped, and the emulator clock was advanced seven hours to make the cache stale.

- A cold launch completed in 1.947 seconds with no fatal exception or ANR.
- Airplane mode plus a background/foreground cycle produced the existing confirmed offline state and kept Retry available without restarting the process.
- Disabling airplane mode plus another background/foreground cycle recovered the healthy add-on while the second add-on remained unavailable.
- The fixture server recorded `GET /manifest.json` before `GET /catalog/movie/f07.json`, confirming manifest-first ordering.
- Home displayed `Recovered Fixture`; Search displayed the fixture; its details page opened with the fixture description.
- Phone and simulated Android tablet layouts rendered recovered Home content.

The initial pass also checked the iPad simulator layout.

During the initial F07 pass, a physical Samsung SM-A528B accepted the full debug APK and cold-launched it without a fatal exception or ANR. The device was locked, so no visible interaction or network-transition claim is made.

## Deferred release checks

- Physical-device Wi-Fi transitions remain deferred. Execute the complete offline-to-online and partial-add-on recovery scenario on a physical iPad.
- Run the interactive transition on iPhone and iPadOS, including Retry and manual refresh.
- Repeat visible Android interaction on an unlocked physical device.
- Manually recheck Continue Watching, Downloads, and offline playback in a release candidate. Their automated regression coverage passed, but this disposable fixture did not exercise those flows.

These are release gates, not reasons to weaken the ordering or cache contract in review.

## Rollback

F07 can be rolled back by removing the coordinator startup and Retry/manual-refresh routing, reverting the generation hooks in affected repositories, and deleting the manifest-cache platform methods. Stored `addon_manifest_cache_<profileId>` values can be discarded safely; they do not contain user downloads or catalog/download caches. The cache decoder is versioned and already treats unsupported data as empty.
