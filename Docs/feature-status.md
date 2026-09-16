# Feature status

This ledger records what `tinyKyuu/NuvioMobile` changes, where each change came
from, which platforms have evidence, and whether an equivalent is available in
official NuvioMobile. It is the detailed companion to the public README.

- Last reviewed: September 16, 2026
- Stable branch: `codex/testflight-internal`
- Stable base reviewed: `a5d37a02ccd5ff37f5511e2e90ffe40117f4d5ce`
- Source version at review: `0.4.12` build `122`
- Reviewed upstream release: `0.4.22-beta`
- Upstream comparison tip: [`95347544`](https://github.com/NuvioMedia/NuvioMobile/commit/95347544858e31d8cf36569a3b154961276ed46e)

## How to read the ledger

Feature status uses these terms:

| Status | Meaning |
| --- | --- |
| Released | Included in a published artifact that was installed and personally verified |
| Merged | Present in the stable source branch but not in a public artifact |
| In testing | Implemented on another branch or pull request and still under validation |
| In development | Implementation is incomplete |
| Planned | Accepted direction without a complete implementation |
| Deferred | Intentionally outside the current release scope |

Platform evidence is literal. A shared source set compiling for Android does
not prove that an Android user flow works. Simulator and emulator evidence is
also kept separate from physical-device testing.

The upstream relationship column uses `Fork addition`, `Adapted`, `Backport`,
or `Now upstream`. `Now upstream` means the README should no longer advertise
the item as a reason to choose this fork, even though this ledger keeps its
history and attribution.

## Product and platform scope

| Platform | Intended scope | Current status | Public artifact |
| --- | --- | --- | --- |
| iOS and iPadOS | Watch Together plus selected mobile and offline improvements | Merged development client; hosted pilot in testing | None |
| Android mobile | Watch Together plus selected mobile and offline improvements | Shared core merged; client and release work in development | None |
| Nuvio desktop | Follow upstream and add a Watch Together adapter | Planned | None |
| Stremio desktop | Add a compatible Watch Together adapter without unrelated client changes | Planned, pending component and license review | None |
| Android TV | No current implementation commitment | Deferred | None |

## Watch Together

| Feature | Origin | Platforms | Status | Upstream relationship | Evidence and limits |
| --- | --- | --- | --- | --- | --- |
| Protocol models and compatibility validation | Fork addition using the public provider-neutral contract | Shared Kotlin for mobile | Merged | No official equivalent found at the reviewed base | [PR #5](https://github.com/tinyKyuu/NuvioMobile/pull/5); source and conformance fixtures only |
| Canonical playback clock, ordering, drift policy, source offsets, and reconnect recovery | Fork addition | Shared Kotlin for mobile | Merged | No official equivalent found at the reviewed base | [PR #5](https://github.com/tinyKyuu/NuvioMobile/pull/5) and [PR #6](https://github.com/tinyKyuu/NuvioMobile/pull/6) |
| Native development room client | Fork addition | iOS and iPadOS | Merged | No official equivalent found at the reviewed base | [PR #6](https://github.com/tinyKyuu/NuvioMobile/pull/6); accepted on two simulators, physical-device testing deferred |
| Hosted pilot client and service-manifest transport | Fork addition | Shared mobile source | In testing | No official equivalent found at the reviewed base | [PR #7](https://github.com/tinyKyuu/NuvioMobile/pull/7); not in the stable branch and no public pilot is claimed |
| Android room UI and player adapter | Fork addition | Android mobile | In development | No official equivalent found at the reviewed base | No supported Android Watch Together artifact yet |
| Nuvio desktop adapter | Fork addition | macOS, Windows, and Linux | Planned | No official equivalent found at the reviewed base | Intended to follow Nuvio desktop upstream apart from integration code |
| Stremio desktop adapter | Fork addition | macOS, Windows, and Linux | Planned | Separate project | Requires a component-level license and integration review |
| Android TV adapter | Fork addition | Android TV | Deferred | No official equivalent found at the reviewed base | Outside the current mobile and desktop plan |

## Downloads and offline use

| Feature | Origin | Platforms | Status | Upstream relationship | Evidence and limits |
| --- | --- | --- | --- | --- | --- |
| Durable direct-file background transfers | Fork addition | iOS and iPadOS | Merged | No equivalent to the fork architecture found at the reviewed base | [PR #2](https://github.com/tinyKyuu/NuvioMobile/pull/2); direct HTTP and HTTPS files only, no HLS, DASH, torrent, or expired-link renewal |
| Persisted queue, network policy, progress, and Live Activity | Fork addition | iOS and iPadOS, with shared tests | Merged | No equivalent to the fork architecture found at the reviewed base | [PR #2](https://github.com/tinyKyuu/NuvioMobile/pull/2); physical iPad background transfer verified |
| Offline playback and native export | Fork addition | iOS and iPadOS | Merged | No equivalent to the fork architecture found at the reviewed base | [PR #2](https://github.com/tinyKyuu/NuvioMobile/pull/2); public binary not released |
| Download the active player source | Fork addition | Shared mobile UI and state | Merged | No equivalent found at the reviewed base | [PR #3](https://github.com/tinyKyuu/NuvioMobile/pull/3); iOS simulator interaction verified, broader release QA remains |
| Bulk download selection and removal | Fork addition | Shared mobile UI and state | Merged | No equivalent found at the reviewed base | [PR #4](https://github.com/tinyKyuu/NuvioMobile/pull/4); simulator and automated checks passed, physical batch-removal QA deferred |
| Offline metadata, artwork, details, and Library browsing | Fork addition | Shared mobile source | Merged | No equivalent found at the reviewed base | [PR #18](https://github.com/tinyKyuu/NuvioMobile/pull/18) and [PR #19](https://github.com/tinyKyuu/NuvioMobile/pull/19); iOS runtime verified, Android host tests and build passed |
| Offline Home with local Continue Watching and Downloaded rows | Fork addition | Shared mobile source | Merged | No equivalent found at the reviewed base | [PR #20](https://github.com/tinyKyuu/NuvioMobile/pull/20); iPhone and iPad simulators verified, Android host tests and build passed |
| Expired download URL recovery | Fork addition | iOS and iPadOS | Deferred | Upstream issue exists, no fork release claim | Parked until its retry and authorization boundaries receive a separate review |
| Signed Android APK | Distribution work | Android mobile | Planned | Not applicable | No public signing key, APK, checksum, or source tag has been published |

## Client fixes and mobile improvements

| Change | Origin | Platforms | Status | Upstream relationship | Evidence and limits |
| --- | --- | --- | --- | --- | --- |
| Bundled CJK subtitle font for the iOS MPV player | Fork fix | iOS and iPadOS | Merged | Tracked in NuvioMedia issue 1832 at integration time | [PR #1](https://github.com/tinyKyuu/NuvioMobile/pull/1); verified on the affected physical iPad |
| Multiline TTML cue support | Official Nuvio backport | Shared player code | Merged | Backport | [PR #8](https://github.com/tinyKyuu/NuvioMobile/pull/8); device validation deferred |
| Subtitle picker selection and persistence across source changes | Official Nuvio backport | Shared player code | Merged | Backport | [PR #9](https://github.com/tinyKyuu/NuvioMobile/pull/9); automated and simulator build evidence, physical interaction deferred |
| Add-on season artwork and certification metadata | Official Nuvio backport | Shared mobile code | Merged | Backport | [PR #10](https://github.com/tinyKyuu/NuvioMobile/pull/10) with the null-Specials correction in [PR #13](https://github.com/tinyKyuu/NuvioMobile/pull/13) |
| Resume refresh and cancellable next-episode prompts | Official Nuvio backport | Shared player code | Merged | Backport | [PR #11](https://github.com/tinyKyuu/NuvioMobile/pull/11); remaining interactive and physical-device checks are recorded in the PR |
| Compose 1.12 dependency alignment | Official Nuvio backport | iOS and Android | Merged | Backport | [PR #12](https://github.com/tinyKyuu/NuvioMobile/pull/12); builds and tests passed, the reported accessibility crash was not reproduced or proven fixed |
| Reduced duplicate startup collection and catalog processing | Official Nuvio backport | Shared mobile code | Merged | Backport | [PR #14](https://github.com/tinyKyuu/NuvioMobile/pull/14); behavior tests passed, no startup benchmark was claimed |
| Release temporary GIF decoding resources | Official Nuvio backport | iOS and iPadOS | Merged | Backport | [PR #15](https://github.com/tinyKyuu/NuvioMobile/pull/15); malformed GIF, memory-growth, and physical-device checks were deferred |
| Hardware keyboard playback controls | Adapted from [`luqmanfadlli/NuvioMobile-Enhanced`](https://github.com/luqmanfadlli/NuvioMobile-Enhanced) | iOS, iPadOS, and Android | Merged | Adapted | [PR #16](https://github.com/tinyKyuu/NuvioMobile/pull/16); simulator and emulator verified, physical keyboards deferred |
| Player brightness can reach the device minimum | Official Nuvio backport | iOS, iPadOS, and Android | Merged | Backport | [PR #17](https://github.com/tinyKyuu/NuvioMobile/pull/17); Android emulator verified, physical display checks deferred |
| Shared catalog and Continue Watching poster sizing, device-local Automatic phone/tablet sizing, Extra Large, and a one-time Poster default migration | Adapted from official Nuvio commit [`cf4674a`](https://github.com/NuvioMedia/NuvioMobile/commit/cf4674a81c88eade150f12a27f7313b1296ccea1), with fork-local responsive sizing and persistence | iOS, iPadOS, and Android | Merged | Adapted; official upstream supplied the shared sizing base, while Automatic sizing, Extra Large, the local size-storage split, and migration rules are fork additions | [PR #22](https://github.com/tinyKyuu/NuvioMobile/pull/22); 965 Android-host tests, Android build, and iOS simulator compile/build passed; the iPad simulator directly verified Automatic and Extra Large, while the iPhone simulator launched and rotated and the phone Automatic size was verified by the resolver test; physical-device and narrow split-view checks are deferred |
| Bottom tablet root-navigation dock and `Settings` fourth-tab label | Fork addition | iPadOS and Android tablets, with the label shared on phones | Merged | Fork addition; no official equivalent found at the reviewed base | [PR #23](https://github.com/tinyKyuu/NuvioMobile/pull/23); the combined base passed 967 Android-host tests, Android debug assembly, and iOS simulator compile/build; iPad and Android tablet simulator/emulator checks cover portrait, landscape, safe-area clearance, offline Retry placement, and RTL, while physical tablet and iPad split-view checks are deferred |
| Detail-page default order migration, Text-first season selector, and duplicate multi-season heading cleanup | Fork-specific section order, Text fallback, and migration behavior; duplicate-heading cleanup and matched toggle styling adapted from official Nuvio commit [`c9f12a70`](https://github.com/NuvioMedia/NuvioMobile/commit/c9f12a703c43a029b548c5bf7630c3dee6fbfa62) | iOS, iPadOS, and Android | Merged | Adapted; only the duplicate-heading cleanup and toggle styling come from official upstream, while the section order, Text default, and conservative profile/sync migration are fork additions | [PR #25](https://github.com/tinyKyuu/NuvioMobile/pull/25); 978 Android-host tests, both Android debug variants, iOS simulator compilation, and the Xcode simulator build passed; iPhone and iPad settings checks covered fresh defaults, Reset, exact legacy migration, and custom-payload preservation; Pixel 8 headless startup passed; content-fixture, authenticated sync/profile-switch, Android visual/tablet, and physical-device checks are deferred |
| Ordered add-on and catalog recovery after connectivity returns, with a bounded per-profile manifest cache | Fork state machine and cache design, with delayed-state presentation adapted from official Nuvio commit [`085e8dc6`](https://github.com/NuvioMedia/NuvioMobile/commit/085e8dc6aaf5072541130be852a782998fc3dbad) | iOS, iPadOS, and Android shared source | In testing | Adapted; official upstream has delayed loading/error states but no equivalent central recovery coordinator or bounded, versioned per-profile manifest cache at the reviewed tip | [PR #26](https://github.com/tinyKyuu/NuvioMobile/pull/26) and [F07 review evidence](../F07-REVIEW.md); implementation head `0ec271ae`; 1,013 Android-host tests, both Android debug variants, Kotlin/Native test compilation, and the unsigned full-distribution Xcode simulator build passed. The 34 focused tests include independent Home catalog publication with fresh manifests, warm-row retention through partial and failed final refreshes, Retry through stale Online then confirmed NoInternet and Online, duplicate coalescing, and concurrent cache persistence with profile isolation. Repository tests inject catalog loaders or storage; they do not model a complete cold offline app launch. The rebuilt iPhone simulator app installed and cold-launched. Android transition evidence from the initial pass predates these fixes. Interactive iOS recovery and physical-device Wi-Fi transitions remain deferred. |

## Release and distribution work

| Item | Status | Exit condition |
| --- | --- | --- |
| Public Android beta | Planned | Complete Android Watch Together validation, create a distinct application ID and long-lived signing key, publish the APK, checksum, certificate fingerprint, source tag, notices, and install guide |
| Public iOS beta | Planned | Complete branding and distribution review, choose the beta channel, publish matching source and notices, and validate the distributed artifact |
| Hosted pilot | In testing | Finish the operations rollout and live private authorization checks, merge the reviewed client, publish a safe setup and privacy guide, and admit a controlled tester group |
| Public desktop builds | Planned | Stabilize the shared protocol, implement client adapters, pass conformance and interoperability checks, and review each client's license and distribution terms |

## Upstream review rule

Before every public release, compare this ledger with the current official
NuvioMobile branch and release notes. If upstream now provides equivalent
behavior:

1. Change the relationship to `Now upstream`.
2. Remove the item from the README's fork-difference table.
3. Keep the provenance and release history here.
4. Retire redundant fork code when doing so does not break stored data,
   Watch Together, or supported release behavior.

The fork synchronizes upstream through a dedicated review branch and pull
request. It does not merge an untested upstream tip directly into a signed or
stable build.
