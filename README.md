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

Last reviewed on September 16, 2026.

| Item | Current state |
| --- | --- |
| Stable source branch | [`codex/testflight-internal`](https://github.com/tinyKyuu/NuvioMobile/tree/codex/testflight-internal) |
| Source version | `0.4.12` build `121` |
| Upstream comparison | Based on NuvioMobile commit [`e68cbb0`](https://github.com/NuvioMedia/NuvioMobile/commit/e68cbb08415c81630f7955b7c1b6ed8d1fdd6e28); selected later fixes are integrated separately |
| Latest upstream release reviewed | [`0.4.22-beta`](https://github.com/NuvioMedia/NuvioMobile/releases/tag/0.4.22-beta) |
| Public binaries | None |
| Watch Together | Shared core and an iOS development client are merged; hosted pilot client is still in testing |

Merged source is not the same as a supported release. The
[feature ledger](./Docs/feature-status.md) records platform evidence, open work,
upstream provenance, and known validation gaps.

## Platform plans

| Platform | Watch Together | Other fork improvements | Distribution |
| --- | --- | --- | --- |
| iOS and iPadOS | Development client merged; hosted pilot in testing | Active mobile scope | Internal development only |
| Android mobile | Shared core merged; client integration in development | Active mobile scope | No signed public APK yet |
| macOS, Windows, and Linux | Planned adapters | Follow the chosen upstream client | No fork build |
| Android TV | Deferred | Out of the current scope | None |

## What this fork changes

| Area | Fork work | Status |
| --- | --- | --- |
| Watch Together | Content-blind protocol models, canonical playback clock, command ordering, drift correction, reconnect handling, and an iOS development client | Merged |
| Hosted pilot | Approved-host sign-in, accountless guest admission, private room updates, and secure local session storage | [In testing](https://github.com/tinyKyuu/NuvioMobile/pull/7) |
| Downloads | Durable iOS background transfers, persisted queue state, network policy, player download controls, and bulk removal | Merged |
| Offline use | Local playback, export, offline metadata and artwork, downloaded Library layouts, and an offline Home experience | Merged |
| Mobile player | Hardware keyboard controls plus selected subtitle, playback, accessibility, brightness, and performance updates; limits are recorded in the ledger | Merged |
| Desktop | Watch Together adapters without unrelated client changes | Planned |

No row in this table promises a public artifact. See the ledger for the exact
platform and test boundary of each claim.

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
See [Internal TestFlight notes](./IOS_INTERNAL_TESTFLIGHT.md) for the current
archive checks and local signing setup.

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
