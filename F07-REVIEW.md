# F07 network recovery review

Status: final correction complete on `codex/f07-network-recovery`; [PR #26](https://github.com/tinyKyuu/NuvioMobile/pull/26) is open for organizer review. Do not merge or release from this note.

- Stable base: `a5d37a02ccd5ff37f5511e2e90ffe40117f4d5ce`
- Reviewed head superseded by this correction: `563e1950a0d69d3504f0f8653cecf5c5fd64e795`
- Tested implementation: `a0e3eaf9d09983e056a5a9e267ed2e39ea77caee`
- Version/build: `0.4.12` / `122` (unchanged)
- Review date: September 17, 2026
- Pull request: [#26](https://github.com/tinyKyuu/NuvioMobile/pull/26)

## Final correction diagnosis

The six correction tests were written against production seams before their implementations changed. The initial consolidated red run executed 69 tests and failed exactly six tests, one for each requested finding. A seventh Android loss-edge regression was added after the controlled runtime pass exposed stale capabilities during `onLost()`; that test also failed for the intended reason before the loss mapping changed.

| Finding | Diagnosed root cause | Red regression | Resulting contract |
| --- | --- | --- | --- |
| Forced probe during an active probe | `requestRefresh()` returned the active generation for every overlapping request, so an availability edge or Reconnect was discarded and the old result could consume the only wake-up. | `availability during an active offline probe queues one fresh probe and one recovery`; retained `repeated Reconnect requests share one probe and a failed probe permits another attempt`. | One forced pending generation is reserved behind the active probe. Further forced requests reuse it, failure-confirmation intent is promoted, the pending probe starts in `finally`, and only its fresh Online result can start recovery. No offline timer was added. |
| Android path validation | `onAvailable()` emitted `Available` before validation, and `NET_CAPABILITY_INTERNET` was treated as sufficient. `distinctUntilChanged()` could then suppress the later validation edge. The runtime pass also showed `onLost()` re-reading stale validated capabilities. | `android recovery availability requires validated internet capability`; follow-up `android default path loss is unavailable even while active capabilities are stale`. | `onAvailable()` emits nothing. Capability changes require both INTERNET and VALIDATED. Default-path loss is unconditionally Unavailable. The path is only a wake-up; HTTP still decides Online, NoInternet, or ServersUnreachable. |
| Settings card refresh | The Settings wrapper accepted only `(String) -> Unit`, so the repository defaulted `forceRefresh` to false even though the selected URL was correct. | `addon card refresh force reloads only its selected manifest`. | A card calls `AddonRepository.refreshAddon(selectedManifestUrl, true)` and never calls the global recovery/refresh-all coordinator. |
| Manual force-all partial publication | The manifest collector notified the coordinator only for `Changed`, so a successful `Unchanged` provider could not publish until the whole force-all batch settled. | `manual force all publishes an unchanged provider before another provider settles`. | Every successful force-all result, Changed or Unchanged, emits a provider-specific partial event as it settles. All enabled URLs are attempted and the final full reconciliation still runs after the batch. |
| Changed-provider scope | `onManifestRecovered(String)` did not describe why a URL was ready. The coordinator accumulated every callback URL, so a later changed A reused the initial cumulative `{A, B}` set. | `changed stale provider reconciles without refetching an unrelated ready provider`. | `ManifestRecoveryEvent` distinguishes cached admission, missing recovery, stale change, and manual force result. Only cached admission builds the initial cumulative set; changed, newly recovered, and force-all results reconcile their one provider. |
| Home reset lifetime | The app-shell producer used `remember`, while Home persisted its acknowledged generation with `rememberSaveable`. After saved-state restoration the consumer could be ahead of the new producer and reject the next real transition. | `saved state restoration cannot suppress the next real presentation transition`; retained `hidden Home consumes a pending presentation reset once without replay`. | One profile-scoped app-shell `HomePresentationResetState` owns both production and consumption. Activity recreation resets both sides together; hidden Home consumes a pending token once and returning later cannot replay it. |

### Organizer follow-up: failed force-all probe

The organizer sequence exposed one lifecycle gap after the six corrections. A force-all retry stores both `retryProbeGeneration` and `forceAllPendingUntilOnline`. When the matching probe settled offline, the controller cleared only the generation. A later automatic reconnect correctly ran normal recovery, but the stale force flag survived and converted a still later ordinary Reconnect into `forceAll = true`.

The checked-in regression `failed manual refresh does not turn a later ordinary Reconnect into force-all` records this trace:

```text
offline generation 1
retry(forceAllManifests = true) -> fresh probe generation 2
generation 2 -> NoInternet -> clear retry generation and force-all intent together
automatic Online generation 3 -> recover(forceAll = false, trigger = Reconnect)
offline generation 4
ordinary retry -> fresh probe generation 5
generation 5 -> Online -> recover(forceAll = false, trigger = Retry)
recorded forceAll attempts -> [false, false]
```

Before the fix, the final assertion failed with `expected: <[false, false]> but was: <[false, true]>`. The controller now clears both fields inside the same `transitionLock` branch. The existing successful-manual-refresh regression still proves that a matching Online result consumes force-all exactly once.

## Traced event sequences

### Probe and path events

The active-probe collision regression records this sequence through the production controller seam:

```text
probe 1 -> NoInternet (confirmed offline)
probe 2 -> held active
Android/path Available -> reserve pending generation 3
two more forced requests -> generation 3, generation 3
probe 2 -> NoInternet
probe 3 -> starts after probe 2 settles -> Online
recovery transitions -> exactly 1
```

This is a three-probe, one-recovery trace. No delay is scheduled after confirmed NoInternet; only a platform event, explicit Reconnect, or foreground confirmation can request the next probe.

The Android decision trace is:

```text
onAvailable -> no event
INTERNET without VALIDATED -> Unavailable
INTERNET plus VALIDATED -> Available -> HTTP probe
onLost, even with stale active capabilities -> Unavailable -> debounced HTTP confirmation
```

### Manifest and catalog reconciliation

Automatic recovery now has explicit event meaning:

```text
valid cached A, B -> CachedProviderAdmitted(A), then CachedProviderAdmitted(B)
                    -> cumulative initial passes {A}, then {A, B}
stale A Changed   -> StaleProviderChanged(A) -> affected-provider pass {A}
missing B Changed -> MissingProviderRecovered(B) -> affected-provider pass {B}
stale Unchanged   -> timestamp renewal only; no provider-specific pass
batch settled     -> final full pass (readyManifestUrls = null)
                    -> await Home, Search, and Discover -> Completed
```

Manual force-all preserves early publication without changing the final boundary:

```text
attempt A and B
A -> Unchanged -> ManualForceResult(A) -> partial pass {A}
B -> still held (A is already published)
B -> failure
batch settled -> final full pass -> Completed/Failed according to global reconciliation
```

Failures keep a usable cached manifest. Missing providers remain excluded until successful recovery. A changed provider never causes an unrelated valid provider to be fetched during its affected-provider pass.

### Settings refresh and Home reset

The Settings trace is `selected card URL -> refreshAddon(url, forceRefresh = true)`. The global coordinator is not involved.

The Home trace is `confirmed presentation change -> app-shell generation +1 -> Home consume(token) -> scrollToItem(0)`. Duplicate conditions, probes, recompositions, and manifest work do not increment the token. If Home is hidden, consumption waits; the same token is rejected on return. After activity restoration, the app-shell-owned producer and consumer start a new session together, so the next real transition is accepted.

## Preserved F07 behavior

- Android uses `ConnectivityManager.NetworkCallback`; iOS uses `NWPathMonitor`. HTTP probes remain authoritative.
- Online has no timer. NoInternet has no polling loop. ServersUnreachable keeps only the bounded foreground retry window at 5, 15, and 30 seconds.
- Manual Reconnect is owned by `NetworkRecoveryCoordinator.retry()` and keeps confirmed-offline Home local-only while a probe is active.
- Confirmed offline Home hides the remote hero and remote rows atomically, filters Continue Watching to locally playable titles, and shows Downloaded content. Online omits the Downloaded row.
- Stale valid manifests remain usable during background validation; failed validation retains them and missing manifests wait.
- Profile ownership, cancellation, generation guards, single-flight refreshes, partial publication, and the final full reconciliation remain intact.
- Recovery stays visible until Home, Search, and Discover reconciliation settles. `Back online` is eligible only after a successful, currently-online `Completed` generation.
- Hero artwork still falls back from banner to poster to the neutral themed surface. No navigation redesign was introduced.

## Regression and build evidence

### Red evidence

The initial pre-implementation run covered the four affected test classes and produced:

```text
69 tests, 6 failures, 0 errors, 0 skipped
```

The failures were the six primary regressions named in the diagnosis table. After the first Android runtime attempt showed stale validated capabilities at default-path loss, the added loss-edge test produced:

```text
1 test, 1 assertion failure
NetworkConnectivityRecoveryTest > android default path loss is unavailable even while active capabilities are stale
```

The organizer follow-up regression then failed before its production change:

```text
1 test, 1 failure, 0 errors, 0 skipped
NetworkRecoveryCoordinatorTest > failed manual refresh does not turn a later ordinary Reconnect into force-all
expected: <[false, false]> but was: <[false, true]>
```

### Focused F07 suite

The final focused command covered connectivity, coordinator, boundary, Home/Search/Details integration, manifest recovery, tabs, Home/hero, and Settings-card behavior:

```text
./gradlew -Pnuvio.ios.distribution=appstore \
  -Pnuvio.android.distribution=full \
  :composeApp:testAndroidHostTest \
  --tests 'com.nuvio.app.core.network.NetworkConnectivityRecoveryTest' \
  --tests 'com.nuvio.app.core.network.NetworkRecoveryBoundaryTest' \
  --tests 'com.nuvio.app.core.network.NetworkRecoveryCoordinatorTest' \
  --tests 'com.nuvio.app.core.network.NetworkRecoveryHomeIntegrationTest' \
  --tests 'com.nuvio.app.core.network.NetworkRecoverySearchIntegrationTest' \
  --tests 'com.nuvio.app.core.network.NetworkRecoveryDetailsTest' \
  --tests 'com.nuvio.app.features.addons.ManifestRecoveryTest' \
  --tests 'com.nuvio.app.MainTabsDestinationTest' \
  --tests 'com.nuvio.app.features.home.HomeScreenTest' \
  --tests 'com.nuvio.app.features.home.components.HomeHeroSectionTest' \
  --tests 'com.nuvio.app.features.addons.AddonsScreenTest' \
  --rerun-tasks --console=plain

BUILD SUCCESSFUL in 51s
125 tests, 0 failures, 0 errors, 0 skipped
```

### Complete matrix

| Check | Exact final result |
| --- | --- |
| Complete Android-host suite + Full debug APK | `BUILD SUCCESSFUL in 1m 36s`; 1,072 tests, 0 failures, 0 errors, 0 skipped; 69 tasks executed |
| Full debug artifact | `androidApp/build/outputs/apk/full/debug/androidApp-full-debug.apk`; 158,020,632 bytes |
| Play Store debug APK | `BUILD SUCCESSFUL in 56s`; 63 tasks executed |
| Play Store debug artifact | `androidApp/build/outputs/apk/playstore/debug/androidApp-playstore-debug.apk`; 155,611,651 bytes |
| Kotlin/Native iOS simulator test target | `:composeApp:compileTestKotlinIosSimulatorArm64`; `BUILD SUCCESSFUL in 46s`; 24 tasks executed |
| Unsigned Full iOS simulator app | `xcodebuild ... CODE_SIGNING_ALLOWED=NO`; `** BUILD SUCCEEDED **` |

The Android and Kotlin commands used the Android Studio JBR, Android SDK at `/Users/muharrem/Library/Android/sdk`, and the configured Nuvio engine at `/Users/muharrem/Documents/ChatGPT/Nuvio iOS/build/nuvio-engine`. The Xcode product is `/private/tmp/nuvio-f07-organizer-followup-derived/Build/Products/Debug-iphonesimulator/Nuvio.app`.

## Controlled Android foreground runtime

The final Full APK was installed on a Pixel 8 API 36 emulator as `com.nuviodebug.com`. Nuvio remained the top-resumed activity; there was no relaunch, tab change, or background/foreground shortcut.

1. Startup probes 1 and 2 classified Online.
2. Airplane mode produced probe 3 `NoInternet`. Accessibility exposed `No internet connection · Reconnect` while Home remained in its local presentation.
3. Activating the root control while airplane mode remained enabled produced one fresh probe 4 `NoInternet` and returned to the actionable offline state.
4. Disabling airplane mode produced probe 5 `Online` from the validated Android path edge. Recovery generation 2 logged `RestoringAddons`, `RefreshingCatalogs`, and `Completed`; the reconnect control was absent after completion.
5. A bounded 10-second emulator transport delay made `Reconnecting…` observable in the live accessibility tree during a follow-up restoration.
6. A temporary eight-second local response hold on the already configured `10.0.2.2:8766` manifest made `Restoring content…` observable. Recovery generation 4 remained in reconciliation until the hold released, logged `Completed` at `11:08:18.615`, and only then did the accessibility tree contain no reconnect/restoring control.

The temporary fixture and emulator were stopped after verification. The configured catalog endpoints do not provide a durable production fixture, so this pass does not claim rendered remote catalog rows. Repository/controller regressions provide deterministic evidence for partial publication, provider-only reconciliation, final settlement, warm-content retention, and completion gating.

The active-probe/availability collision was exercised through the controlled production `NetworkStatusController` seam: the older probe was held, Available reserved one pending probe, repeated forced requests coalesced, the old NoInternet result settled, the pending Online result ran, and recovery fired once.

## Provenance

- Official NuvioMobile was reviewed through [`95347544`](https://github.com/NuvioMedia/NuvioMobile/commit/95347544858e31d8cf36569a3b154961276ed46e). Official commit [`085e8dc6`](https://github.com/NuvioMedia/NuvioMobile/commit/085e8dc6aaf5072541130be852a782998fc3dbad) is the delayed loading/error-state presentation source; it does not provide this coordinator, event-driven path observation, or cache contract.
- Official draft [PR #1897](https://github.com/NuvioMedia/NuvioMobile/pull/1897), reviewed at `929757d0e4193eadc2c0b0e9eda19de54b6e88b3`, is the donor for the six-hour manifest-cache freshness intent. Its unversioned, unbounded draft cache was not copied.
- Official [issue #1612](https://github.com/NuvioMedia/NuvioMobile/issues/1612) records related stale profile/manifest/Home ordering symptoms and was closed as not planned.
- `luqmanfadlli/NuvioMobile-Enhanced` at `db904462af18570cf023d69b57c9632efea816f9` and `AKRusso/NuvioMobile-Enhanced` at `ac03c46a` were checked; neither supplies an equivalent recovery state machine.

## Deferred organizer and release checks

No merge, TestFlight upload, signed release, version change, or public artifact was produced.

Physical iPad and Android interaction remains deferred. Interactive iOS path switching is also deferred because `simctl` has no supported Wi-Fi/airplane control. The release-candidate checklist must cover physical foreground Wi-Fi off/on, automatic restoration after a long offline interval, failed manual Reconnect, visible and hidden-Home one-shot scroll resets, Home hero/row synchronization, light/dark/AMOLED styling, Downloads, local Continue Watching, offline Details, local playback, and signed/TestFlight release-candidate work.

## Rollback

Rollback is source-only: remove the platform path monitor and root observer, restore direct status retry routing, revert typed manifest recovery events and provider-scoped reconciliation, restore the prior Home reset ownership, and remove the coordinator completion join. Stored `addon_manifest_cache_<profileId>` values remain safe to discard; they contain no downloads or catalog-result databases. Unsupported cache schema versions already decode as empty.
