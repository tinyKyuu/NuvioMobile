# F03 Offline Home review

## Scope

This follow-up starts from `08dee1be78dfa8032d3b860e0a0be2b40b729897` on `codex/testflight-internal`. It fixes the offline iPad Home behavior documented in [NuvioMedia/NuvioMobile#1968](https://github.com/NuvioMedia/NuvioMobile/issues/1968) without changing download storage or playback behavior.

## Behavior

- On landscape tablets, a connection failure now appears as a compact Nuvio pill at the top right of the root Home, Search, Library, and Profile tabs. The pill keeps `Retry` to the left of a crossed-globe icon, exposes the connection state in a tooltip, and triggers the existing forced network refresh.
- The status pill is limited to root tabs. Details and playback screens do not show it.
- Root tab content receives enough top clearance for the floating navigation, preventing Home content and connection messaging from sitting underneath the tab bar.
- Offline Home shows Continue Watching first and Downloaded second. Downloaded remains an offline-only row, while the remote hero, catalog rows, and collection rows stay out of the offline layout.
- The large connection card is reserved for the empty offline state, when neither resumable downloaded content nor a playable download is available.
- Offline Continue Watching cards resolve matching downloaded artwork from verified local files. Episode artwork is preferred, followed by the local background and poster. Remote image fields are cleared when a matching offline title has no local value, so a disconnected card does not keep retrying inaccessible URLs.
- The existing poster-card option is available at **Settings → General → Layout → Continue Watching → Card Style → Poster**. The page is named `Appearance` internally, but its English UI label is `Layout`.

## Validation

| Check | Result |
|---|---:|
| Focused Android host suite | 2 suites, 37 tests, passed |
| Focused iOS simulator suite | 2 suites, 37 tests, passed |
| Clean iOS simulator app build | Passed |
| Landscape iPad runtime review | Compact top-right Retry pill, unobstructed navigation, Continue Watching first, local episode artwork, Downloaded second, and no remote catalog verified |
| Diff whitespace check | Passed |

The focused tests cover offline Continue Watching filtering, local artwork replacement and fallbacks, preservation of playback progress, and root-only visibility for connection failures.

## Visual evidence

Before, the full connection card overlapped the navigation area, Downloaded preceded Continue Watching, and the resumable card could not use the matching locally cached image:

![Offline Home before the fix](review-assets/f03-offline-home-polish/before-offline-home-ipad.png)

After, Retry is a compact top-right pill and the locally useful rows are ordered for offline playback:

![Offline Home after the fix](review-assets/f03-offline-home-polish/after-offline-home-ipad.png)

## Physical-device review

On an iPad in landscape, download an episode, play part of it, then disconnect and open Home. Verify that Continue Watching uses the downloaded episode image, appears above Downloaded, and resumes playback. Confirm that the Retry pill appears on each root tab, has a connection tooltip, and disappears on a title details screen.

## Rollback

Revert this follow-up commit. No database migration or offline-file cleanup is required.
