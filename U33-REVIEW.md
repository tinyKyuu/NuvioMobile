# U33 review

## Scope and provenance

- Target branch: `codex/testflight-internal`
- Approved base: `cba2bd4f88fd964617f644b5af506ac900117c7f`
- Upstream source: `e306a0a00d67ee060cafd0d26311e887f20f88ce`, `perf(ios): release temporary GIF decoding resources`
- Upstream stable patch ID: `0230d132927588eb7078c8e68739a419ecf11f7e`
- Upstream default branch: `cmp-rewrite`
- Upstream tip rechecked from the remote: `9bc77bc48e0cc4958006657f129190169d831e33`

The import changes one production file: `CollectionCardRemoteImage.ios.kt`. GIF bytes now enter a decoder that owns the temporary Core Foundation data object it creates. The decoder releases that object, the image source, and every successfully created frame image on all success, failure, and early-return paths.

The upstream production hunk applied unchanged. There are no production adaptations or omitted hunks. The fork file matched the upstream parent before import, and the base-to-production stable patch ID matches upstream. The source commit is an ancestor of the checked upstream tip, and no later commit through that tip changes the U33 path.

GitHub reports no associated pull request and no commit comments for the source commit. The review therefore found no public discussion that changes the implementation or its intended scope.

The shared organizer files `UPSTREAM_REVIEW.md` and `UPSTREAM_REVIEW_DATA.json` were left unchanged.

## Ownership review

The ownership model follows Apple's Create Rule:

- `CFDataCreate` returns an owned object. The outer `finally` releases it exactly once with `CFRelease`.
- `CGImageSourceCreateWithData` returns an owned object. The nested `finally` releases it exactly once with `CFRelease`.
- `CGImageSourceCreateImageAtIndex` returns an owned frame image when successful. The per-frame `finally` releases it exactly once with `CGImageRelease` after constructing the `UIImage`.
- Null creation results are not released. The code does not release any Get-rule reference.
- Empty-frame returns, animated-image creation failure, decode exceptions, and nonlocal returns from the inline `runCatching` block all traverse the applicable `finally` blocks.

Primary references:

- [CFDataCreate](https://developer.apple.com/documentation/corefoundation/cfdatacreate%28_%3A_%3A_%3A%29?language=objc)
- [CGImageSourceCreateWithData](https://developer.apple.com/documentation/imageio/cgimagesourcecreatewithdata%28_%3A_%3A%29?changes=_10&language=objc)
- [CGImageSourceCreateImageAtIndex](https://developer.apple.com/documentation/imageio/cgimagesourcecreateimageatindex%28_%3A_%3A_%3A%29?language=objc)
- [CFRelease](https://developer.apple.com/documentation/corefoundation/cfrelease?changes=_9)

## Baseline

Before import, the approved base compiled the iOS Simulator arm64 Kotlin target from a clean worktree with `--rerun-tasks`. Gradle completed 16 tasks successfully in 46 seconds. The compiler emitted only existing warnings.

Evidence:

- `build/u33-evidence/baseline/compile-ios-simulator.log`

The dependency setup used the repository's checksum-pinned Nuvio Engine `0.1.1` preparation script and initialized MPVKit at the revision recorded by the repository. These generated and ignored files did not change the Git diff.

## Final validation

The production source is committed as `251b86dd501fbee67ceaf1860b7cfa073199fa2f`. Its one-file production diff against the approved base has stable patch ID `0230d132927588eb7078c8e68739a419ecf11f7e`, matching upstream.

No decoder unit test was added. The decoder is private, and exposing it only for a return-value test would change the exact upstream hunk without proving Core Foundation retain counts. Ownership was instead checked path by path, then the surrounding home tests were run on both host and Kotlin/Native targets. Valid animated GIF loading, malformed data fallback, repeated loading, and memory behavior remain explicit runtime checks rather than claims from these tests.

### Android host tests

The focused home-feature run used `--rerun-tasks`:

```text
9 suites
54 passed, 0 failed, 0 errors, 0 skipped
```

Coverage included home catalog definition, parsing, sections, settings sync, collection definitions, `HomeScreen`, continue-watching artwork, and hero-section tests.

Evidence:

- `build/u33-evidence/final-host/gradle.log`
- `build/u33-evidence/final-host/xml/`

### Kotlin/Native iOS Simulator tests

The same 54 tests passed on the iOS Simulator arm64 target with zero failures, errors, or skips. The run used iPhone 17 Pro simulator `0D237DF4-F2F0-4058-81B1-E7729A20535B` on iOS 26.5. The repository's iOS regression script forced fresh native execution and compiled the real `PluginCryptoBridge.swift` into an arm64 Mach-O object. The linker reported unavailable external cryptography module-cache debug symbols, but linking and test execution succeeded.

Evidence:

- `build/u33-evidence/final-native/gradle.log`
- `build/u33-evidence/final-native/xml/`
- `build/ios-player-test-bridge/PluginCryptoBridge.o`

### Full-distribution iOS Simulator app

Xcode 26.6 with the iOS 26.5 SDK built the normal `iosApp` scheme from exact production commit `251b86dd501fbee67ceaf1860b7cfa073199fa2f`. The build used Debug, a generic iOS Simulator destination, full distribution, disabled automatic package resolution, and disabled code signing. It completed with `** BUILD SUCCEEDED **`.

The output app is `build/u33-final-simulator/Build/Products/Debug-iphonesimulator/Nuvio.app`. Its bundle ID is `com.tinykyuu.nuvio.internal`, version is `0.4.12`, and build is `120`. The bundle contains `NotoSansCJKsc-Regular.otf` and the downloads widget. Xcode emitted non-fatal minimum-deployment-version and duplicate-static-library warnings, and skipped App Intents metadata because the app has no AppIntents dependency.

Evidence:

- `build/u33-evidence/final-build/xcodebuild.log`
- Launcher SHA-256: `271c3779f617de7c5327cefd4545cc880f7cede92d6844cd139b78d56d1db350`
- Debug dylib SHA-256: `6749b967eb73d39f034ac72e301d7abd03626f36d3f412050077e315934a0fb9`

### Interactive simulator smoke test

The built app was installed and launched on an isolated iPhone 17 Pro simulator running iOS 26.5. Guest continuation, local profile creation, Cinemeta add-on installation, and the populated Home UI completed successfully. This confirms that the built full-distribution app starts and reaches the surrounding collection and home experience. It does not claim that the GIF decoder path was exercised.

Evidence:

- `build/u33-evidence/runtime/launch.png`

## Preservation check

The base-to-production commit changes only `composeApp/src/iosMain/kotlin/com/nuvio/app/features/home/components/CollectionCardRemoteImage.ios.kt`. It does not change metadata or certification behavior, subtitle or player code, download and offline paths, app identity, signing, version, distribution, Compose, CryptoKit, fonts, account state, or Watch Together. This review-note commit changes documentation only.

## Review limits and physical-device checks

No physical-device run, release archive, TestFlight upload, or merge was performed. A device follow-up should load a collection with a valid animated GIF, verify a malformed GIF falls back without a crash, and repeatedly load and scroll GIF-backed collection cards while watching memory for sustained growth. Those checks would validate platform decoding and memory behavior beyond the ownership proof, native tests, simulator build, and app smoke test recorded here.
