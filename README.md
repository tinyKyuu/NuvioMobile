<div align="center">

  <h1>Nuvio Watch Together (Unofficial)</h1>

  <p>
    A community-maintained NuvioMobile fork centered on provider-neutral
    Watch Together, with selected improvements for mobile clients.
  </p>

  [Feature status](./Docs/feature-status.md) · [Watch Together protocol](https://github.com/tinyKyuu/watch-together) · [Official Nuvio](https://github.com/NuvioMedia/NuvioMobile)

</div>

> [!IMPORTANT]
> This is an unofficial modified version of NuvioMobile. It is not affiliated
> with or endorsed by NuvioMedia or Stremio. No public APK, IPA, TestFlight, or
> desktop build is available from this fork yet.

## What this project is

Watch Together is the reason this fork exists. It coordinates playback between
people who each select and play their own source. The public protocol is kept
separate from Nuvio so other clients can implement compatible adapters.

The product scope is deliberately narrow:

- iOS, iPadOS, and Android are the mobile clients. They receive Watch Together
  and selected mobile improvements, including the fork's download and offline
  work.
- Future desktop clients should follow their upstream applications and add only
  the Watch Together integration needed for interoperability.
- Android TV support is deferred.

The current repository name and product name are provisional. Public binary
distribution will use a distinct application identity and will not use the
official Nuvio logo without permission.

## Current status

Last reviewed on September 17, 2026.

| Item | Current state |
| --- | --- |
| Stable source branch | [`codex/testflight-internal`](https://github.com/tinyKyuu/NuvioMobile/tree/codex/testflight-internal) |
| Source version | `0.4.12` build `123` |
| Upstream comparison | Reviewed through NuvioMobile commit [`95347544`](https://github.com/NuvioMedia/NuvioMobile/commit/95347544858e31d8cf36569a3b154961276ed46e); selected later fixes are integrated separately |
| Latest upstream release reviewed | [`0.4.22-beta`](https://github.com/NuvioMedia/NuvioMobile/releases/tag/0.4.22-beta) |
| Public binaries | None |
| Watch Together | Shared core and an iOS development client are merged; hosted pilot client is still in testing |

Merged source is not the same as a supported release. The
[feature ledger](./Docs/feature-status.md) records platform evidence, open work,
upstream provenance, and known validation gaps.

## Project scope at a glance

| Platform | Watch Together | Other fork improvements | Distribution |
| --- | --- | --- | --- |
| iOS and iPadOS | Development client merged; hosted pilot in testing | Active mobile scope | Internal development only |
| Android mobile | Shared core merged; client integration in development | Active mobile scope | No signed public APK yet |
| macOS, Windows, and Linux | Planned adapters | Follow the chosen upstream client | No fork build |
| Android TV | Deferred | Out of the current scope | None |

## What this fork changes

The tables below group user-facing changes by the part of the app where they
appear. `Merged` means the source is in the stable branch. It does not mean a
public binary is available. See the [feature ledger](./Docs/feature-status.md)
for provenance, pull requests, test evidence, and remaining validation work.

### Watch Together

| Feature | Where | Platforms | Status |
| --- | --- | --- | --- |
| Synchronized rooms with collaborative pause, resume, seeking, 10-second jumps, reconnect recovery, and drift correction | Settings and Player | iOS and iPadOS client, shared mobile core | Merged |
| Local source alignment, so participants can use independently selected sources with different intro lengths | During room playback | iOS and iPadOS client, shared mobile core | Merged |
| Content-blind protocol that keeps titles, episode IDs, providers, source URLs, credentials, and viewing history out of the relay | Room protocol | Shared across compatible clients | Merged |
| Approved-host sign-in, accountless guest admission, private room updates, and secure session storage | Settings and room lobby | Shared mobile source | [In testing](https://github.com/tinyKyuu/NuvioMobile/pull/7) |

### Downloads and offline use

| Feature | Where | Platforms | Status |
| --- | --- | --- | --- |
| Durable direct-file downloads that continue while the app is in the background | Downloads | iOS and iPadOS | Merged |
| Persisted queue state, two active transfer slots, pause and resume, and Wi-Fi-only network policy | Downloads | iOS and iPadOS | Merged |
| Download the exact direct-file source currently playing without leaving the player | Player | Shared mobile source, iOS tested | Merged |
| Select and remove several current transfers from Download activity, or completed files from Library > Downloads, without mixing the two sets | Download activity and Library > Downloads | iOS, iPadOS, and Android shared source | [Merged](https://github.com/tinyKyuu/NuvioMobile/pull/27) |
| Play completed files offline and export them through the native share sheet | Downloads and Details | iOS and iPadOS | Merged |
| Browse and manage completed movies, shows, seasons, and episodes from Library > Downloads; view current transfers with compact Download settings pinned above them on one Downloads page | Library, Downloads, and Settings | iOS, iPadOS, and Android shared source | [Merged](https://github.com/tinyKyuu/NuvioMobile/pull/27) with a [merged refinement](https://github.com/tinyKyuu/NuvioMobile/pull/32) |

### Home

| Feature | Where | Platforms | Status |
| --- | --- | --- | --- |
| Offline Home keeps Continue Watching and Downloaded content available while remote catalog rows stay hidden | Home | Shared mobile source, iOS tested | Merged |
| Header-integrated connection status keeps one finite Reconnect session trailing on Home, Search, and Library, uses a quiet idle action with a stronger restoring state, removes its Wi-Fi-off graphic before stacking, and includes a transient offline test switch | Home, Search, Library, and Advanced settings | Shared mobile source; iPhone and iPad simulators tested | [Merged](https://github.com/tinyKyuu/NuvioMobile/pull/32) |
| Continue Watching can reuse verified local artwork for downloaded titles | Home | Shared mobile source, iOS tested | Merged |
| Foreground network restoration revalidates connectivity, restores affected add-ons and catalogs, preserves local offline Home, and coalesces manual reconnect work | Home, Search, Discover, Details, and add-on Settings | iOS, iPadOS, and Android shared source; Android emulator and cross-platform builds tested | [Merged](https://github.com/tinyKyuu/NuvioMobile/pull/26) |

### Navigation and appearance

| Feature | Where | Platforms | Status |
| --- | --- | --- | --- |
| Catalog and Continue Watching posters share one size setting. Automatic is stored on the device and chooses a phone or tablet size, Extra Large is available, and Continue Watching defaults to Poster. | Home, catalogs, and Settings | iOS, iPadOS, and Android | Merged |
| iPad root navigation uses one persistent native dock with a traveling selector, Liquid Glass on iPadOS 26+, a bottom layout for portrait/narrow windows, and a reserved right-side strip for wide landscape windows. The dock hides for the software keyboard and Downloads management. iPhone keeps Apple's native tab navigation. Android tablets retain their shared Compose dock and Adaptive, Expanded, Compact, and Classic choices. Exact native-animation parity and the remaining device/window checks are not yet accepted. | App navigation | iOS, iPadOS, and Android tablets | [In testing](https://github.com/tinyKyuu/NuvioMobile/pull/34) |

### Library

| Feature | Where | Platforms | Status |
| --- | --- | --- | --- |
| Downloaded movies and series appear in the Library with horizontal shelves or a vertical poster grid | Library | Shared mobile source, iOS tested | Merged |
| Download posters support Browse and Manage modes, partial/full show selection, exact size totals, contextual play/remove/share actions, and a shared phone/tablet manager | Library > Downloads | iOS, iPadOS, and Android shared source | [Merged](https://github.com/tinyKyuu/NuvioMobile/pull/27) |
| Local metadata, title artwork, episode artwork, and principal cast images remain available offline | Library and Details | Shared mobile source, iOS tested | Merged |
| Adaptive All titles, Downloads, and eligible Cloud files views share measured controls; All titles deduplicates local saves, the selected tracking library, and playable downloads without changing membership, watch status and current-scope genres are filterable, and poster status and overflow controls share one reusable badge treatment | Library | iOS, iPadOS, and Android shared source | [Merged](https://github.com/tinyKyuu/NuvioMobile/pull/32) |
| Downloaded episodes remain playable from the normal series details screen and expose an exact-episode removal shortcut; shared watched, downloaded, and internet-required badges replace repeated labels, while offline Details keeps a stable season heading with an inline `All | Downloaded` episode selector | Details | iOS, iPadOS, and Android shared source | [Merged](https://github.com/tinyKyuu/NuvioMobile/pull/32) |

### Player

| Feature | Where | Platforms | Status |
| --- | --- | --- | --- |
| Hardware keyboard controls for play, pause, 10-second seeking, and leaving the player | Player | iOS, iPadOS, and Android | Merged |
| Brightness gestures can reach the device minimum and restore the previous value when the player closes | Player | iOS, iPadOS, and Android | Merged |
| Resume state refresh and cancellable next-episode prompts | Player | Shared mobile source | Merged |

### Subtitles

| Feature | Where | Platforms | Status |
| --- | --- | --- | --- |
| Bundled CJK font fallback prevents missing Chinese glyphs in the native player | Player | iOS and iPadOS | Merged |
| Multiline TTML cues retain their intended line breaks | Player | Shared mobile source | Merged |
| Subtitle selection stays consistent across picker updates and source changes | Player | Shared mobile source | Merged |

### Details and discovery

| Feature | Where | Platforms | Status |
| --- | --- | --- | --- |
| Add-on metadata can supply season artwork and regional certification details | Details | Shared mobile source | Merged |
| A null Specials poster no longer shifts artwork onto the wrong regular season | Series details | Shared mobile source | Merged |
| Detail pages place Episodes after Production by default while preserving custom layouts. Series season selection defaults to Text, keeps a saved Posters choice, and does not repeat the selected season heading below a multi-season selector. | Details and Settings | iOS, iPadOS, and Android | Merged |

### Performance and reliability

| Change | Where | Platforms | Status |
| --- | --- | --- | --- |
| Compose 1.12 dependency alignment for current mobile builds; the earlier accessibility crash was not reproduced or claimed fixed | App framework | iOS and Android | Merged |
| Collection and catalog startup work avoids duplicate decoding and stops scanning after the requested valid item limit | App startup and Home | Shared mobile code | Merged |
| Temporary GIF decoding resources are released after frame conversion | Animated artwork | iOS and iPadOS | Merged |

## Watch Together privacy boundary

Watch Together synchronizes playback state. It does not transport media.

Each participant resolves and plays a source through their own configuration.
The protocol does not send the relay a title, movie or episode identifier,
provider name, source URL, manifest URL, torrent, subtitle URL, request header,
cookie, debrid credential, account token, or viewing-history identifier.

Rooms use playback rounds, readiness, relay time, position, pause state, and
private client-side source offsets. Different sources can have different intro
lengths, so a client can align its local timeline without revealing the source.

The public schemas, conformance fixtures, and privacy design live in
[`tinyKyuu/watch-together`](https://github.com/tinyKyuu/watch-together).

## Availability

There is no public beta download yet. The repository is public so the code,
design, and progress can be reviewed while mobile clients and the hosted pilot
are still being tested.

The intended release path is:

1. Finish controlled iOS and Android mobile validation.
2. Publish a signed Android APK with its checksum and matching source tag.
3. Choose an iOS beta route after the branding and distribution review.
4. Add desktop adapters after the shared protocol and mobile pilot are stable.

If you want the normal Nuvio release today, use the
[official NuvioMobile project](https://github.com/NuvioMedia/NuvioMobile).

## Relationship with upstream

This fork tracks NuvioMobile, but it does not merge every upstream commit as it
lands. Before a fork release, upstream changes receive a separate review and
regression pass so downloads, offline state, Watch Together, signing, and app
identity are not lost during synchronization.

Generally useful fixes remain candidates for contribution to NuvioMedia. When
upstream ships an equivalent feature, the public ledger marks it as available
upstream and this README stops presenting it as a fork distinction.

## Build from source

Clone the stable integration branch:

```bash
git clone --branch codex/testflight-internal https://github.com/tinyKyuu/NuvioMobile.git
cd NuvioMobile
```

### Android

Android development requires Android Studio and the Android SDK.

```bash
./gradlew :androidApp:assembleFullDebug
```

This produces a local debug build. It is not the planned signed public APK.

### iOS and iPadOS

iOS development requires macOS and Xcode. Configure the public server settings
locally, then prepare the native dependencies:

```bash
./scripts/configure-official-nuvio-server.sh
./scripts/prepare-ios-dependencies.sh
```

Build the simulator target:

```bash
env NUVIO_IOS_DISTRIBUTION=full xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Debug \
  -sdk iphonesimulator \
  -derivedDataPath build/ios-derived-full-simulator \
  CODE_SIGNING_ALLOWED=NO \
  build
```

The existing archive workflow produces an internal-only TestFlight export. It
cannot be promoted to external TestFlight testing or released on the App Store.
See [Internal TestFlight notes](./Docs/distribution/ios-internal-testflight.md) for the current
archive checks and local signing setup.

Trakt and Simkl sign-in require private build configuration. See
[Tracking provider build configuration](./Docs/distribution/tracking-providers.md)
for the supported redirects, local placeholders, and verification command.

## Reporting problems

Report fork behavior in this repository first. Include the commit or build,
platform, installation method, and reproduction steps. Do not include room
credentials, media URLs, account data, request headers, or private source
information.

Open an issue with NuvioMedia only when the problem also reproduces on the
current official Nuvio build without fork-only configuration. Watch Together
relay, admission, or tester-account problems belong here, not in the upstream
tracker.

## Contributing

Read [CONTRIBUTING.md](./CONTRIBUTING.md) before opening an issue or pull
request. Large features and behavior changes require prior approval. Focused
bug fixes, tests, documentation corrections, and carefully scoped platform
work are easier to review.

## License and attribution

This fork and its modifications are released under the
[GNU General Public License v3.0](./LICENSE). Upstream copyright and attribution
notices remain in effect. Distributed binaries must be accompanied by the
corresponding source and required notices.

- [NuvioMobile](https://github.com/NuvioMedia/NuvioMobile)
- [Nuvio website](https://nuvio.tv)
- [Support Nuvio](https://nuvio.tv/support)
