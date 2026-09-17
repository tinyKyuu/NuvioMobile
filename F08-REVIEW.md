# F08A review

Status: ready for review on `codex/f08a-library-downloads`, based on approved commit `01d2b8c5`.

## Scope and result

- Completed media management now lives in Library > Downloads.
- Download activity contains only current, queued, paused, and failed transfers, with bulk selection and confirmed removal for those visible transfers. Successful removals leave selection, while failed current transfers remain visible and selected for retry.
- Settings > Downloads contains network and storage policy, with a `Manage downloads` handoff to Library.
- One download-ID selection model drives posters, the collapsed manager, root groups, seasons, and episodes.
- The phone manager is a modal sheet; the tablet manager is a bounded centered dialog.
- Movie, show, season, and episode actions use the shared manager and structured removal result.
- Details keeps local episode playback and an exact-episode removal shortcut. Episode cards now give their long-press action the accessibility label `More actions`.
- F08B visual hardening, F08C artwork repair, F09 navigation work, expired-URL recovery, adaptive-stream downloads, season download creation, and database migrations remain out of scope.

No app version, build number, schema, or migration changed.

## Removal contract

Removal is cleanup-first and per-ID:

1. Resolve the requested catalog records and active handles without mutating or detaching repository state.
2. Cancel the active handle or platform task first. A cancellation failure stops cleanup and leaves the original record and handle in place.
3. For completed media, clean the request and partial file before deleting the playable file. Any earlier failure stops the target before playable-media deletion.
4. Treat completed-media deletion as the final irreversible step. A successful deletion is committed as removed; a deletion failure keeps the record and file.
5. For current transfers, cancellation is the irreversible step. The record is removed after cancellation succeeds, while request or partial-file failures are reported as ancillary cleanup warnings instead of retaining a canceled transfer.
6. Commit successful IDs once, then reconcile offline metadata and unreferenced artwork from the committed catalog state.
7. Report exact successful IDs, retained failed IDs, cleanup warnings, and bytes reclaimed from files that were actually removed.
8. Publish the settled state once and pump the scheduler once after the batch.

The tests cover callback order, the retained-media invariant, active-cancel failure, request and partial cleanup failure, completed-file failure, mixed batches, exact reclaimed-byte accounting, single state application, one final publication, one scheduler pump, Activity selection outcomes, Activity and Policy scoping, the existing Library reducer, and cleanup-warning classification.

## Automated verification

Final source state on September 17, 2026:

| Check | Result |
| --- | --- |
| Focused Android F08A suites: `DownloadsBatchRemovalTest`, `DownloadsSelectionLogicTest`, `DownloadLibraryManagementTest`, `DownloadNavigationDecisionTest` | Passed, 30 tests; 0 failed, errored, or skipped |
| Android host suite: `:composeApp:testAndroidHostTest` | Passed, 1,096 tests; 0 failed, errored, or skipped |
| Focused iOS-native F08A suites: the same four suites | Passed, 30 tests; 0 failed, errored, or skipped |
| Kotlin/Native iOS simulator compilation | Passed through `compileKotlinIosSimulatorArm64`, `compileTestKotlinIosSimulatorArm64`, and the focused native test link/run |
| Full-policy Android: `:androidApp:assembleFullDebug` | Passed |
| Play Store-policy Android: `:androidApp:assemblePlayStoreDebug` | Passed |
| App Store-policy unsigned iOS simulator Xcode build | `** BUILD SUCCEEDED **` for version `0.4.12` build `122` |
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

- The Full-policy APK installed in place on a Pixel 8 API 36 emulator and preserved the existing profile.
- Library Downloads showed the empty state and disabled Manage.
- Download activity contained only active-state categories, and system Back returned to Library.
- Settings > Downloads showed Manage downloads plus Wi-Fi, cellular, expensive-network, Low Data Mode, and storage-policy controls; it did not show completed media. Activating Manage downloads returned directly to Library > Downloads.
- The emulator's existing local development server was unavailable, so network-backed Home content was not part of this offline F08A pass.
- The final Activity partial-failure correction was not exercised destructively at runtime because the connected emulator had no disposable current-transfer fixture. Its success, partial-failure, total-failure, scope, and cleanup-warning states are covered by deterministic common tests on Android and iOS-native.

### Private iPad

- The earlier F08A head used the existing bundle identifier `com.tinykyuu.nuvio.internal`, team, version `0.4.12`, and build `122` for an in-place install and launch on `iPad8,1`.
- That install retained the existing data container. The organizer-review fixes were verified with native tests and an unsigned simulator build only.
- This review-fix pass did not build for, install on, uninstall from, reset, copy a container to, or alter downloads on the private iPad.

## Runtime regression found and fixed

The first iOS phone pass exposed that Download activity reused `DownloadsSettingsRoute`, whose settings-destination marker made Back return to Settings even when Library opened it. F08A now has a separate non-settings `DownloadActivityRoute`; policy remains on `DownloadsSettingsRoute`. A regression test asserts their different preferred-tab behavior, and both iOS simulator and Android emulator Back navigation returned to Library afterward.

## Organizer review blockers fixed

- Completed cleanup now deletes playable media last and stops before that step after cancellation, request, or partial cleanup failure. Failed completed targets remain playable and cataloged. Once a current transfer is canceled, its record is removed even if ancillary request or partial cleanup reports a warning, so Activity cannot retain a detached Downloading or queued record.
- Download activity again supports Select, long-press entry, row toggles, select all, clear, Done, Back-to-exit-selection, confirmation, and bulk removal. Its candidate IDs come only from the displayed current-transfer list. Hidden completed records cannot enter Activity selection; Settings policy exposes no selection behavior; Library remains the completed-download manager.
- Activity bulk removal now consumes the structured batch result. Successful IDs leave selection; failed or otherwise unremoved visible IDs stay selected and keep selection mode active. All-success exits selection. Success, partial failure, and total failure receive distinct feedback, while cleanup warnings attached to successfully canceled transfers remain warnings and do not retain those transfers.

## Deferred review

- Broad light/dark/AMOLED, poster-style, animation, Dynamic Type, and assistive-technology hardening remains F08B. F08A uses the existing theme and depth tokens, and its dark phone/tablet runtime surfaces were inspected, but this review does not claim an exhaustive theme/accessibility matrix.
- Reacher Continue Watching artwork repair remains F08C and requires separate runtime reproduction.
- Destructive runtime cleanup was intentionally not invoked against simulator fixtures, the emulator's existing profile, or private-device data. The structured cleanup and partial-failure contract is covered by deterministic tests.

## Rollback

Revert the bounded F08A commit. There is no schema migration or version change to unwind.
