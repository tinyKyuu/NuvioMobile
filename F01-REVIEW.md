# F01 hardware keyboard player controls

## Scope and baseline

Approved for iOS/iPadOS and Android. Space toggles playback, Left/Right seek by 10 seconds, Escape exits through the existing progress path. F02, F03, U25 and unrelated changes are excluded.

The worktree started detached at `e68cbb08415c81630f7955b7c1b6ed8d1fdd6e28`. It had no tracked changes and a generated local signing file. Created `codex/f01-hardware-keyboard-controls` from approved integration commit `1392a98ee16e517c2b223cd31d18287719641afc`. No organizer files were changed or copied.

Read F01-HANDOFF.md completely, FEATURE_REQUEST_REVIEW.md, and UPSTREAM_REVIEW.md queue, preservation and integration guidance in the organizer checkout. No applicable AGENTS.md was found. Applied unslop and permission-first-troubleshooting skills.

Baseline source evidence in `build/f01-evidence` records the exact SHA and absence of keyboard handlers in common, Android, iOS and Swift player paths. This establishes missing input handling; it is not a reproduced physical-accessory test. The initial baseline host invocation could not configure before the pinned Apple engine dependency was prepared. The implementation tests passed after dependency preparation.

## Provenance and adaptations

Reference: [Luqman Fadlli's GPLv3 keyboard commit](https://github.com/luqmanfadlli/NuvioMobile-Enhanced/commit/c4e7d4213052d0dd467be45f52fbfa79665a9b77), `c4e7d4213052d0dd467be45f52fbfa79665a9b77`. Adapted shared runtime action routing and the UIKit bridge, retaining attribution in the shared shortcut source. No wholesale donor merge.

Fresh GitHub API inspection on 2026-09-15 found upstream NuvioMedia/NuvioMobile PR #997 open at `43ef0c81355c63310b8d9232eea74c5030c5c855`, updated 2026-09-12. No issue comments, inline comments or reviews were returned. Its author's checked test list does not validate this adaptation.

## Implementation

- Android uses one Compose preview-key owner, native Android key codes and a hardware-keyboard source filter. D-pad/gamepad navigation remains outside this handler.
- iOS uses one UIKit press owner and forwards wire-coded actions through the existing Kotlin bridge. Compose does not dispatch iOS keys.
- Unmodified Space, Left, Right and Escape produce one action per press. Held/repeated downs are consumed without repeating actions; Shift, Control, Alt and Command/Meta combinations pass through.
- Shared runtime actions call the existing toggle, 10-second seek and progress-flush/back paths, including Watch Together requests. Swift does not control playback independently.
- Player panels, text-entry dialogs, download confirmations, locked controls, timeline scrubbing, hold-to-speed, background/window focus loss and PiP suppress shortcuts. Playback keys also require the current ready controller and no player error. Hidden controls remain usable and become visible after playback/seek actions.
- Android restores focus on entry, foreground return and final panel dismissal. iOS restores its first responder when re-enabled. Controller listeners are disabled and removed on replacement/disposal; stale-controller callbacks recheck ownership.

## Validation

Build logs, test reports and Android screenshots are under this worktree's ignored `build/f01-evidence` directory. iPad simulator UI evidence is also present in the implementation task's tool history. The PR records the exact tested branch SHA.

Automated validation passed:

- Android full-distribution debug app: `:androidApp:assembleFullDebug`.
- Full iOS Debug simulator app using `iosApp/iosApp.xcodeproj`, the Nuvio scheme and worktree-local derived data.
- Android host player tests plus `StreamResumeStateTest`.
- Matching iOS native player/resume tests through `scripts/test-ios-player-regressions.sh`, including the repository's Swift crypto test bridge.
- Final focused keyboard tests on Android and iOS. They distinguish common action policy from Android native source/key/repeat mapping. Coverage includes modifiers, duplicate/held downs, release/reset, disabled input, hidden local-file playback, unready/stale/error controllers, panels including subtitle/search/Watch Together dialogs, locks, scrub/speed gestures and one-shot Escape.
- `git diff --check` and a source scan for temporary keyboard logging.

Dedicated runtime instances used isolated F01 profiles and a loopback fixture addon; no real profile/addon data was changed.

| Platform | Runtime evidence | Remaining checks |
| --- | --- | --- |
| iOS/iPadOS | Dedicated iPad simulator `2F677371-F32F-4D4A-8D32-B371B6FC73C9`: Space pause/resume, Left/Right seek, Control+Right ignored, subtitle-panel suppression, immediate focus recovery after dismissal, Escape back to streams with `Resume from 0:41`. Simulator keyboard capture was released afterward. | Real attached/Bluetooth keyboard; held-key behavior through UIKit; background/PiP, locks, Watch Together text entry/room requests, source/episode replacement, start/end boundaries and downloaded playback during a complete native keyboard session. These have common policy/source or existing regression coverage where applicable, not interactive device proof. |
| Android | Dedicated `F01_Keyboard_API_36` emulator: Space pause/resume, Left/Right route, held Right produces one seek, Control+Right ignored. Right advanced exactly 10 seconds. Backward seek used the existing path and showed the unresolved native seek discrepancy. | Real attached/USB/Bluetooth keyboard; clean Escape/progress runtime retry; panel/text focus recovery, background/PiP, locks, source/episode replacement, Watch Together room requests, boundaries and downloaded playback during a complete native keyboard session. Common policy/source tests cover gating and action routing. |

The Android Escape capture was inconclusive after the emulator/app stalled under repeated UI automation. A restart did not restore its ADB session. Escape's one-shot shared action passed in both test targets, and iOS verified navigation plus persisted progress; Android end-to-end Escape remains open.

The existing native EOF/backward-seek issue remains separate and is not claimed fixed. Existing touch callbacks and player engine seek behavior were retained. Full distribution, identity, signing/version, dependencies, accounts/profiles, downloads, subtitles/audio, resume/next-episode and Watch Together production behavior received no unrelated changes.

## Review disposition

Implementation complete for focused PR review. Physical-device and incomplete runtime checks above are explicit review follow-ups, not waived by earlier feature deferrals. Commit and push only F01 files, open one PR against `codex/testflight-internal`, then stop before merge. Organizer files, F02, F03 and U25 remain outside this change.
