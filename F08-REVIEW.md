# F08A review

Status: ready for review on `codex/f08a-library-downloads`, based on approved commit `01d2b8c5`.

## Scope and result

- Completed media management now lives in Library > Downloads.
- Download activity contains only current, queued, paused, and failed transfers.
- Settings > Downloads contains network and storage policy, with a `Manage downloads` handoff to Library.
- One download-ID selection model drives posters, the collapsed manager, root groups, seasons, and episodes.
- The phone manager is a modal sheet; the tablet manager is a bounded centered dialog.
- Movie, show, season, and episode actions use the shared manager and structured removal result.
- Details keeps local episode playback and an exact-episode removal shortcut. Episode cards now give their long-press action the accessibility label `More actions`.
- F08B visual hardening, F08C artwork repair, F09 navigation work, expired-URL recovery, adaptive-stream downloads, season download creation, and database migrations remain out of scope.

No app version, build number, schema, or migration changed.

## Removal contract

Removal is cleanup-first and per-ID:

1. Resolve the requested catalog records without mutating repository state.
2. Stop platform work and clean the media file, temporary file, and request payload for each target.
3. Commit only successful IDs to the catalog after cleanup settles.
4. Reconcile offline metadata and unreferenced artwork from the successfully committed catalog state.
5. Keep failed targets cataloged and selected so the user can retry.
6. Report successful IDs, per-ID failure reasons, and bytes reclaimed from successful records only.
7. Publish reconciled state once and pump the scheduler once after the batch.

The tests cover scope filtering, no early catalog mutation, single commit, request-storage failure, mixed success/failure, failed-ID retention, and exact reclaimed-byte accounting.

## Automated verification

Final source state on September 17, 2026:

| Check | Result |
| --- | --- |
| Android host suite: `:composeApp:testAndroidHostTest` | Passed, 1,086 tests; 0 failed, errored, or skipped |
| Focused iOS-native suites: `DownloadsBatchRemovalTest`, `DownloadLibraryManagementTest`, `DownloadNavigationDecisionTest` | Passed, 16 tests; 0 failed, errored, or skipped |
| Full-policy Android: `:androidApp:assembleFullDebug` | Passed; `androidApp-full-debug.apk`, 161,691,755 bytes |
| Play Store-policy Android: `:androidApp:assemblePlayStoreDebug` | Passed; `androidApp-playStore-debug.apk`, 156,925,153 bytes |
| App Store-policy iOS simulator Xcode build | Passed for iPhone/iPad simulator, including the final accessibility-label change |
| App Store-policy signed iPad Xcode build | Passed for `iPad8,1`, version `0.4.12` build `122` |
| Patch hygiene | `git diff --check` passed |

The build logs contain existing Kotlin/deprecation and minimum-deployment warnings; none failed a task or were introduced as an F08A functional regression.

## Runtime evidence

### iPhone simulator

- Empty state: Library exposes Saved, Downloads, and Cloud; Downloads shows the dedicated empty state and disables Manage.
- Populated offline fixture: one downloaded series episode remained intact after in-place installs and appeared as `1 episode(s) • 9.7 MB`.
- Manage mode showed the collapsed zero-selection bar, full poster selection, accent check state, and exact `0 movie(s) + 1 episode(s) • 9.7 MB` totals.
- The phone manager opened as a modal sheet and drilled from All downloads to the show, Season 1, and the exact episode.
- Episode actions were ordered Play downloaded file, Select, Remove download, Share. Show actions offered Choose episodes, Select all 1 episodes, and Remove all 1 downloads without a show-level Share action.
- The downloaded episode played locally from the normal details screen.
- Download activity contained no completed item, and Back returned to Library after the route-stack regression described below was fixed.

The simulator accessibility bridge cannot synthesize Compose's press-and-hold gesture. It therefore did not open the details-page long-press overlay during automation. That shortcut is present in both episode layouts, resolves the exact season/episode download, carries a `More actions` accessibility label, and compiles in the final iOS and Android builds. The exact-episode Remove download action was rendered and inspected through the Library manager; no destructive confirmation was accepted.

### iPad simulator

- A simulator-only copy of the populated offline fixture preserved the original fixture and supplied the same 9.7 MB episode.
- Library Downloads, Manage, selection, exact counts, group/season/episode drill-down, and the bounded centered dialog were inspected with the Library visibly dimmed behind it.
- Bottom navigation clearance remained intact. No download was removed.

### Android emulator

- The final Full-policy APK installed in place on a Pixel 8 API 36 emulator and preserved the existing profile.
- Library Downloads showed the empty state and disabled Manage.
- Download activity contained only active-state categories, and system Back returned to Library.
- Settings > Downloads showed Manage downloads plus Wi-Fi, cellular, expensive-network, Low Data Mode, and storage-policy controls; it did not show completed media. Activating Manage downloads returned directly to Library > Downloads.
- The emulator's existing local development server was unavailable, so network-backed Home content was not part of this offline F08A pass.

### Private iPad

- The App Store-policy build used the existing bundle identifier `com.tinykyuu.nuvio.internal`, team, version `0.4.12`, and build `122`.
- The final source state installed in place on `iPad8,1`; no uninstall, reset, container copy, trust-setting change, or destructive download action occurred.
- Install output retained data-container UUID `A5B7E827-0BD7-463F-AB8E-B571C794A12F`, and the final application launch succeeded.
- This is an install/launch and data-preservation smoke test, not a claim that every F08A visual interaction was manually exercised on the private device.

## Runtime regression found and fixed

The first iOS phone pass exposed that Download activity reused `DownloadsSettingsRoute`, whose settings-destination marker made Back return to Settings even when Library opened it. F08A now has a separate non-settings `DownloadActivityRoute`; policy remains on `DownloadsSettingsRoute`. A regression test asserts their different preferred-tab behavior, and both iOS simulator and Android emulator Back navigation returned to Library afterward.

## Deferred review

- Broad light/dark/AMOLED, poster-style, animation, Dynamic Type, and assistive-technology hardening remains F08B. F08A uses the existing theme and depth tokens, and its dark phone/tablet runtime surfaces were inspected, but this review does not claim an exhaustive theme/accessibility matrix.
- Reacher Continue Watching artwork repair remains F08C and requires separate runtime reproduction.
- Destructive runtime cleanup was intentionally not invoked against simulator fixtures or private-device data. The structured cleanup and partial-failure contract is covered by deterministic tests.

## Rollback

Revert the bounded F08A commit. There is no schema migration or version change to unwind.
