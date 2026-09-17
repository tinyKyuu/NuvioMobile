# F07 network recovery review

Status: correction complete on `codex/f07-network-recovery`; [PR #26](https://github.com/tinyKyuu/NuvioMobile/pull/26) is ready for organizer review. Do not merge or release from this note.

- Stable base: `a5d37a02ccd5ff37f5511e2e90ffe40117f4d5ce`
- Tested implementation: `d84afa64307e12f0c7d88d5aa14a8d06146284ca`
- Version/build: `0.4.12` / `122` (unchanged)
- Review date: September 17, 2026
- Pull request: [#26](https://github.com/tinyKyuu/NuvioMobile/pull/26)

## Final behavior

### Connectivity detection and recovery

Android now observes the default network with `ConnectivityManager.NetworkCallback`; iOS uses `NWPathMonitor`. These observers are wake-up signals only. Every state change is still classified by the existing public-internet and configured-server HTTP probes before the app publishes `Online`, `NoInternet`, or `ServersUnreachable`.

The observer is owned by the foreground app shell and has explicit start, duplicate-start coalescing, cancellation, and disposal behavior. The probe policy is:

- Online has no timer. Startup, foreground entry, explicit Reconnect, and meaningful path changes are the only triggers.
- A possible path loss while Online is debounced, then confirmed by HTTP. An initial failed HTTP classification is checked a second time before the confirmed condition changes.
- NoInternet waits passively for the operating system to report an available path. That event starts one immediate, coalesced HTTP probe; there is no airplane-mode polling loop.
- ServersUnreachable gets at most three foreground-only retries at 5, 15, and 30 seconds. Success, backgrounding, or exhausting the window stops the schedule.
- Backgrounding cancels delayed and fallback work. Foreground entry schedules the existing six-second confirmation probe.

A confirmed offline-like-to-Online transition starts one profile-owned recovery generation. Recovery restores manifests, publishes usable providers as they become ready, then performs a full Home/Search/Discover reconciliation. The final pass now waits for current Home, Search, and Discover work to settle before publishing `Completed`. A renewed confirmed outage cancels the active generation and rejects late completion. Profile changes and replacement generations retain the same ownership guards.

### Reconnect control and completion notification

The root control is derived from one state machine:

1. Confirmed offline and idle: `Offline · Reconnect`.
2. HTTP probe active: spinner and `Reconnecting…`.
3. Catalog recovery active: spinner and `Restoring content…`.
4. Failed probe: return to the actionable offline state.
5. Global recovery failure: `Couldn’t restore content · Reconnect`.
6. Completed recovery: hide the control.

The root action calls `NetworkRecoveryCoordinator.retry()`; it no longer bypasses the coordinator. Repeated presses and automatic events coalesce. During a retry, `isProbing` is separate from the last confirmed condition, so Home remains in its confirmed offline presentation until the HTTP result changes it.

The wide control uses the tablet dock's surface color, chip shape, tonal elevation, and shadow tokens. The compact control has equivalent state descriptions for accessibility. `Back online` is emitted only for a later successful, currently-online `Completed` generation, after catalog reconciliation has returned. A failed or interrupted generation cannot claim success.

### Home presentation, artwork, and scroll ownership

Home uses one presentation value derived from the confirmed network condition:

- Online keeps the remote hero and remote catalog rows together and omits the Downloaded row.
- Confirmed offline hides the remote hero and remote rows atomically, filters Continue Watching to locally playable titles, and shows Downloaded content.
- An unconfirmed probe retains the last confirmed presentation and cached metadata.

Hero artwork tries the item's banner, then its poster, then the neutral themed surface. Catalog and hero metadata remain in memory through transport failures; no second persistence layer was added.

An always-composed, profile-scoped transition tracker increments once for each confirmed Online→Offline and Offline→Online/recovery topology change. Home consumes the pending generation with a non-animated `scrollToItem(0)`. Duplicate states, recomposition, ordinary refresh, and manifest revalidation do not increment it. If Home is on another tab, the generation remains pending in the app shell and is consumed the next time Home is presented.

### Manifest stale-while-revalidate contract

Six hours remains a freshness threshold, not an expiry. Any valid parsed cached manifest remains in the ready-provider set while background validation is active, including when `isRefreshing == true`. Missing or invalid manifests still wait because no usable provider definition exists.

Refresh results are explicit:

- Unchanged: renew the successful-fetch timestamp and clear refresh state without provider-specific catalog reconciliation.
- Changed: atomically publish the new manifest and reconcile only that provider before the final pass.
- Failed: keep the old valid manifest and existing Home/Search/Discover content usable.

The per-URL single-flight, profile/generation ownership, bounded versioned cache, partial healthy-provider publication, and final reconciliation remain intact. Manual `forceAllManifests` still fetches all enabled manifests. The refresh button on an add-on card again calls the per-add-on repository operation; no new global Settings action was added.

## Regression evidence

The first correction tests were added before the implementation change. Both failed on the audited production seams: a stale valid provider was not published while validation was held, and an explicitly ready parsed manifest was excluded when `isRefreshing` was true. The final focused correction suite passed 100 tests with no failures:

```text
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/muharrem/Library/Android/sdk' \
./gradlew -Pnuvio.ios.distribution=appstore \
  -Pnuvio.android.distribution=full \
  :composeApp:testAndroidHostTest \
  --tests 'com.nuvio.app.core.network.NetworkConnectivityRecoveryTest' \
  --tests 'com.nuvio.app.core.network.NetworkRecoveryBoundaryTest' \
  --tests 'com.nuvio.app.core.network.NetworkRecoveryCoordinatorTest' \
  --tests 'com.nuvio.app.core.network.NetworkRecoveryHomeIntegrationTest' \
  --tests 'com.nuvio.app.features.addons.ManifestRecoveryTest' \
  --tests 'com.nuvio.app.MainTabsDestinationTest' \
  --tests 'com.nuvio.app.features.home.HomeScreenTest' \
  --tests 'com.nuvio.app.features.home.components.HomeHeroSectionTest' \
  --tests 'com.nuvio.app.features.addons.AddonsScreenTest' \
  --rerun-tasks --console=plain

BUILD SUCCESSFUL in 55s
100 tests, 0 failures, 0 errors, 0 skipped
```

The case-by-case matrix covers:

| Acceptance case | Final regression evidence |
| --- | --- |
| Automatic foreground restoration | Platform-availability observation triggers a coalesced HTTP probe from confirmed offline and reaches coordinator recovery without a button. Duplicate start/event, cleanup, foreground/background, transient loss confirmation, and no-poll policies are asserted. |
| Manual Reconnect | Retry requests a fresh probe through the coordinator; stale Online cannot start recovery; repeated input coalesces; failed probes remain offline; a successful fresh result starts one generation. |
| Control states | Offline, probing, restoring, global failure, completed/hidden, and compact/wide presentation decisions are covered while the last confirmed condition remains unchanged during a probe. |
| Completion boundary | The coordinator remains in `RefreshingCatalogs` while final reconciliation is suspended. Production Home, Search, and Discover awaiters do not return until their current jobs settle. A renewed outage invalidates late completion. |
| Back online | The notification tracker rejects restoring, failed, duplicate, and completed-while-offline generations and accepts only a later successful online completion. |
| Stale manifests | Held, unchanged, changed, failed, fresh, missing, and force-all paths use the production batch/single-flight seams. Valid stale manifests publish immediately and remain usable in Home, Search, and selected Discover while validation is active. |
| Targeted Settings refresh | The add-on card operation records exactly its selected manifest URL; a separate force-all recovery still attempts every enabled add-on. |
| Atomic Home | The presentation helper switches hero/remote rows and Downloaded visibility together only on confirmed state. Offline content is local-only; probing preserves the prior confirmed mode. |
| Scroll reset | Exactly one reset generation is produced for each confirmed direction, duplicates produce none, and a generation remains pending while Home is not presented. |
| Hero fallback | Metadata survives partial, held, and failed transports. Artwork candidates are banner, distinct poster, then neutral surface. |
| Existing F07 behavior | Cold/warm partial publication, Search empty-versus-failure handling, interrupted Details retry, generation/profile rejection, offline snapshots, Downloaded, Continue Watching, and local playback regressions remain in the complete suite. |

## Complete build matrix

The tested implementation passed the complete Android-host suite and Full debug build:

```text
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/muharrem/Library/Android/sdk' \
./gradlew -Pnuvio.ios.distribution=appstore \
  -Pnuvio.android.distribution=full \
  :composeApp:testAndroidHostTest :androidApp:assembleFullDebug \
  --rerun-tasks --console=plain

BUILD SUCCESSFUL in 1m 39s
1,063 tests, 0 failures, 0 errors, 0 skipped
```

The separate Play Store build passed:

```text
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/muharrem/Library/Android/sdk' \
./gradlew -Pnuvio.ios.distribution=appstore \
  -Pnuvio.android.distribution=playstore \
  :androidApp:assemblePlaystoreDebug \
  --rerun-tasks --console=plain

BUILD SUCCESSFUL in 1m 21s
```

Artifacts:

- `androidApp-full-debug.apk`: 158,015,256 bytes
- `androidApp-playstore-debug.apk`: 155,606,263 bytes

Kotlin/Native simulator test compilation passed:

```text
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/muharrem/Library/Android/sdk' \
NUVIO_ENGINE_ROOT='/Users/muharrem/Documents/ChatGPT/Nuvio iOS/build/nuvio-engine' \
./gradlew -Pnuvio.ios.distribution=full \
  -Pnuvio.android.distribution=full \
  :composeApp:compileTestKotlinIosSimulatorArm64 \
  --rerun-tasks --console=plain

BUILD SUCCESSFUL in 50s
```

The unsigned Full iOS simulator app also passed:

```text
NUVIO_IOS_DISTRIBUTION=full \
NUVIO_ENGINE_ROOT='/Users/muharrem/Documents/ChatGPT/Nuvio iOS/build/nuvio-engine' \
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath /private/tmp/nuvio-f07-final-correction-derived \
  -disableAutomaticPackageResolution \
  build CODE_SIGNING_ALLOWED=NO

** BUILD SUCCEEDED **
```

## Runtime evidence

The final Full APK was installed on a Pixel 8 API 36 emulator as `com.nuviodebug.com`. Nuvio stayed the foreground activity for the complete sequence; there was no relaunch, tab change, or background/foreground trigger.

1. Startup classified Online (`NetworkStatus` generations 1 and 2).
2. Enabling airplane mode produced generation 3 `NoInternet`. Accessibility exposed `No internet connection · Reconnect`, and Home showed the local offline presentation.
3. Activating the root control while airplane mode remained enabled produced a fresh generation 4 `NoInternet` and returned to the same actionable offline state. This is the manual failed-probe path through the coordinator.
4. Disabling airplane mode produced generation 5 `Online` from the Android path callback without a button. Recovery generation 2 logged `RestoringAddons`, `RefreshingCatalogs`, then `Completed`; the root reconnect control disappeared.

The emulator's configured catalog fixture at `10.0.2.2:8765` was not running, so this final runtime pass does not claim rendered remote catalog content. Catalog restoration, completion gating, warm-content retention, and success/failure replacement are covered at production repository/controller seams in the 100 focused tests and retained complete suite. The emulator was stopped after verification.

The unsigned iOS app installed and cold-launched on simulator `68A42C7D-B136-4518-A02B-F4CED41E2986` with bundle ID `com.tinykyuu.nuvio.internal`. `simctl` does not expose a supported Wi-Fi/airplane toggle, so this pass does not claim an interactive iOS path transition. `NWPathMonitor` compilation and observer lifecycle are covered by the iOS build and shared observation tests, respectively.

## Upstream and donor relationship

- Official NuvioMobile was reviewed through [`95347544`](https://github.com/NuvioMedia/NuvioMobile/commit/95347544858e31d8cf36569a3b154961276ed46e). Official commit [`085e8dc6`](https://github.com/NuvioMedia/NuvioMobile/commit/085e8dc6aaf5072541130be852a782998fc3dbad) is the delayed loading/error-state presentation source; it does not provide this coordinator, event-driven path observation, or cache contract.
- Official draft [PR #1897](https://github.com/NuvioMedia/NuvioMobile/pull/1897), reviewed at `929757d0e4193eadc2c0b0e9eda19de54b6e88b3`, is the donor for the six-hour manifest-cache freshness intent. Its unversioned, unbounded draft cache was not copied. F07 adds schema versioning, bounds, profile lifecycle, stale-while-revalidate semantics, single-flight requests, and generation guards.
- Official [issue #1612](https://github.com/NuvioMedia/NuvioMobile/issues/1612) records related stale profile/manifest/Home ordering symptoms and was closed as not planned.
- `luqmanfadlli/NuvioMobile-Enhanced` at `db904462af18570cf023d69b57c9632efea816f9` and `AKRusso/NuvioMobile-Enhanced` at `ac03c46a` were checked; neither supplies an equivalent recovery state machine.

## Deferred organizer and release checks

No merge, TestFlight upload, signed Android release, or public artifact was produced.

Physical iPad and Android interaction remains deferred. The release-candidate checklist must cover foreground Wi-Fi off/on, automatic restoration after a long offline interval, manual Reconnect after a failed probe, Home hero/row synchronization, both one-shot scroll resets including a hidden Home tab, light/dark/AMOLED tablet and compact control styling, Downloads, local Continue Watching, offline Details, and local playback. iOS interactive path switching is part of that physical-device pass.

## Rollback

Rollback is source-only: remove the platform path monitor and root observer, restore direct status retry routing, revert the coordinator completion join and manifest outcome comparison, and remove the presentation reset tracker. Stored `addon_manifest_cache_<profileId>` values remain safe to discard; they contain no downloads or catalog-result databases. Unsupported cache schema versions already decode as empty.
