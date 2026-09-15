# U34 review

## Scope and provenance

- Target branch: `codex/testflight-internal`
- Approved base: `8ae1c796a5bbcb2db476509d071808e04c12c0dc`
- Upstream source: `8b43fd89236d5f7a1d945019971a69ac9104667c`, `perf(startup): avoid redundant collection and catalog processing`
- Upstream stable patch ID: `463c561ce0a89ca96fde9d41b75bb8d12a3f62f1`
- Upstream tip rechecked from the remote: `9bc77bc48e0cc4958006657f129190169d831e33`

The import changes two production files. `CollectionRepository.initialize()` now decodes collections from the `JsonElement` it already parsed and stored. `HomeCatalogParser` now stops scanning input after it has collected `maxItems` valid unique entries. Invalid entries still skip, duplicates still do not count toward the cap, and `rawItemCount` still reports the full input array size.

The two upstream production hunks applied unchanged. There are no production adaptations or omitted hunks. One focused parser test was added because the existing cap test covered duplicates but did not prove that non-object and missing or blank required fields still skip without consuming the cap. No decode-implementation test was added.

The shared organizer files `UPSTREAM_REVIEW.md` and `UPSTREAM_REVIEW_DATA.json` were read as source material and left unchanged. No repository `AGENTS.md`, `plan.md`, or `PLAN.md` exists in this worktree or the organizer checkout.

## Baseline

The focused Android host baseline ran from the approved base with `--rerun-tasks`:

```text
CollectionRepositoryTest: 2 passed
CollectionSourceSerializationTest: 9 passed
HomeCatalogParserTest: 3 passed
Total: 14 passed, 0 failed, 0 errors, 0 skipped
```

Evidence:

- `build/u34-evidence/baseline-host/gradle.log`
- `build/u34-evidence/baseline-host/xml/`
- `build/u34-evidence/prepare-ios-dependencies.log`

The first setup attempts did not execute tests. The shell initially lacked an active Java runtime, then Gradle could not access its user cache within the sandbox, and finally the checkout needed the local engine and Android SDK paths. The successful baseline used the Android Studio JDK, the installed Android SDK, the repository's checksum-pinned iOS dependency preparation script, and the existing full-distribution setting.

## Final validation

The production and test source is committed as `1ebe95add66dead5c3350f5e3f6d830f261163e4`. The two-file production diff against the approved base has stable patch ID `463c561ce0a89ca96fde9d41b75bb8d12a3f62f1`, matching upstream. The source commit is an ancestor of the checked upstream tip, and no later commit through that tip changes either U34 production path.

### Android host tests

The final host run used `--rerun-tasks` and covered the collection package, home catalog tests, home collection definitions, and catalog pagination:

```text
CatalogPaginationStateTest: 4 passed
CollectionRepositoryTest: 2 passed
CollectionSourceSerializationTest: 9 passed
HomeCatalogDefinitionTest: 2 passed
HomeCatalogParserTest: 4 passed
HomeCatalogSectionTest: 3 passed
HomeCatalogSettingsSyncServiceTest: 1 passed
HomeCollectionDefinitionsTest: 1 passed
Total: 26 passed, 0 failed, 0 errors, 0 skipped
```

Evidence:

- `build/u34-evidence/final-host/gradle.log`
- `build/u34-evidence/final-host/xml/`

### Kotlin/Native iOS Simulator tests

The same 26 tests passed on the iOS Simulator arm64 target with zero failures, errors, or skips. The run used iPhone 17 Pro simulator `0D237DF4-F2F0-4058-81B1-E7729A20535B` on iOS 26.5. `scripts/test-ios-player-regressions.sh` forced fresh native test execution and compiled the real `PluginCryptoBridge.swift` into an arm64 Mach-O object for the test binary. The linker reported missing external cryptography module-cache debug symbols, but test linking and execution succeeded.

Evidence:

- `build/u34-evidence/final-native/gradle.log`
- `build/u34-evidence/final-native/xml/`
- `build/ios-player-test-bridge/PluginCryptoBridge.o`

### Full-distribution iOS Simulator app

Xcode 26.6 with the iOS 26.5 SDK built the normal `iosApp` scheme from exact production commit `1ebe95add66dead5c3350f5e3f6d830f261163e4`. The build used the Debug configuration, generic iOS Simulator destination, full distribution, disabled automatic package resolution, and disabled code signing. It completed with `** BUILD SUCCEEDED **`.

The output app is `build/u34-final-simulator/Build/Products/Debug-iphonesimulator/Nuvio.app`. Its bundle ID is `com.tinykyuu.nuvio.internal`, version is `0.4.12`, and build is `120`. The bundle contains `NotoSansCJKsc-Regular.otf` and the downloads widget. Xcode emitted non-fatal minimum-deployment-version warnings from bundled native objects and skipped App Intents metadata because the app has no AppIntents dependency.

Evidence:

- `build/u34-evidence/final-build-1ebe95ad/xcodebuild.log`
- Launcher SHA-256: `1e0d53b6cdd94eeae5f542e734a4f3c32c66fd1d9419a08dd8f412b1a78e69b1`
- Debug dylib SHA-256: `8be83c88825a46dc5fc6b12ce9cc02f95bc3638701fca1d0aabc8bf4607ba39b`

## Preservation check

The base-to-production commit changes only the two U34 production paths and the focused parser test. It does not change metadata or certification behavior, subtitle or player code, download and offline paths, app identity, signing, version, distribution, Compose, CryptoKit, fonts, account state, or Watch Together. The review-note commit that follows changes documentation only.

## Review limits

No startup timing benchmark was run, so this review does not claim a measured startup improvement. The change has no user-facing behavior that needs a physical-device check after the behavior tests and full simulator build passed. No interactive check or physical-device run was performed. No release archive, TestFlight upload, or merge was performed.
