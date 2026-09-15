# Leading null Specials poster follow-up

Base: `9f7bc7a320abccd6252ff63c2193419bad8db633`

Branch: `codex/season-poster-null-specials`

Pull request: `https://github.com/tinyKyuu/NuvioMobile/pull/13`

Upstream source: `60e6a1b5ba925dd90fac7d1dca18f5662cfb60ef` (`fix(details): handle null specials poster placeholders`)

This is a focused follow-up to the U16 season-poster work merged in PR #10. It is not a new U-number. The shared organizer files were read from `/Users/muharrem/Documents/ChatGPT/Nuvio iOS` and remain unchanged.

## Scope

- Preserve the current mapping for equal counts, explicit season 0, nonconsecutive seasons, missing episode metadata, absent artwork, and the ordinary one-based fallback.
- Add the upstream regression suite before production code and capture the old failure.
- Import only the upstream `JsonNull` guard if the fork needs no adaptation.
- Run fresh focused host and Kotlin/Native iOS Simulator tests, then a full-distribution simulator app build.
- Keep evidence under `build/season-poster-evidence/`.
- Do not merge, upload, install on a physical device, or change unrelated parser, UI, download, account, player, signing, version, distribution, or Watch Together code.

## Preflight

- The worktree initially opened detached at upstream `e68cbb08415c81630f7955b7c1b6ed8d1fdd6e28`.
- Refreshed `origin/codex/testflight-internal`; local, remote-tracking, and fetched target all resolve to the approved checkpoint `9f7bc7a320abccd6252ff63c2193419bad8db633`.
- Created this unused feature branch directly from that checkpoint.
- Preserved the worktree-local signing override. It is ignored on the internal branch and will not be committed.
- No repository `AGENTS.md` exists.
- GitHub returned no commit comments or associated pull request for the upstream source SHA.
- No later commits touched `MetaDetailsParser.kt` or `SeasonPosterParsingTest.kt` from the source SHA through upstream `9bc77bc48e0cc4958006657f129190169d831e33`.

## Implementation understanding

With observed regular seasons 1, 2, and 3, the existing fallback maps four poster slots to seasons 1 through 4. If the array is `[null, poster1, poster2, poster3]`, filtering the null after mapping leaves the artwork on seasons 2 through 4. The source change detects only the narrow case where positive observed seasons exist, the poster array has exactly one extra slot, and the first element is JSON null. It maps that first slot to season 0 and the remaining slots to the observed positive seasons. Every earlier mapping branch keeps precedence.

## Validation record

### Existing baseline at `9f7bc7a`

Fresh Android host execution ran `SeasonPosterTest`, `MetaDetailsParserTest`, and `MetaDetailsCertificationTest`. All 30 tests passed with zero failures, errors, or skips. Gradle forced the test task to execute. Evidence: `build/season-poster-evidence/baseline-host/`.

### Old-code reproduction

The unmodified upstream `SeasonPosterParsingTest.kt` was added before production code. Fresh host execution ran six tests. Four passed and two failed:

- `null specials placeholder does not shift regular season posters` expected `{1=season-1.jpg, 2=season-2.jpg, 3=season-3.jpg}` but got `{2=season-1.jpg, 3=season-2.jpg, 4=season-3.jpg}`.
- `null regular season poster keeps its slot after specials placeholder` expected `{1=season-1.jpg, 3=season-3.jpg}` but got `{2=season-1.jpg, 4=season-3.jpg}`.

The remaining equal-count, missing-metadata, nonconsecutive-season, and ordinary fallback cases passed before the fix. Evidence: `build/season-poster-evidence/regression-before-fix/`.

### Imported correction

Applied all production and test hunks from upstream `60e6a1b5ba925dd90fac7d1dca18f5662cfb60ef` without adaptation or omission. The production diff imports `JsonNull` and adds the guarded leading-placeholder mapping branch after the two existing exact-count branches. No certification helper or unrelated parser path changed.

Fresh host rerun of `SeasonPosterParsingTest` passed all six tests with zero failures, errors, or skips. Evidence: `build/season-poster-evidence/regression-after-fix/`.

The combined production-and-test stable patch ID is `c884185ded725cc446edab7854f455354bf0ac6c`, matching the upstream source patch exactly. The production-only stable patch ID also matches upstream at `282620dd52baa8e941a923a2bc2b52ee97581eef`.

### Final host verification at `bba8024d`

Forced a fresh Android host execution of every `com.nuvio.app.features.details.*` test. All 57 tests passed with zero failures, errors, or skips:

- `HeroTrailerSelectorTest`: 5
- `MetaDetailsCertificationTest`: 9
- `MetaDetailsParserTest`: 9
- `MetaDetailsReleaseLineTest`: 6
- `SeasonPosterParsingTest`: 6
- `SeasonPosterTest`: 12
- `SeriesPlaybackResolverTest`: 7
- `SeriesSeasonSupportTest`: 3

Evidence: `build/season-poster-evidence/final-host-bba8024d/`.

### Kotlin/Native iOS Simulator verification at `bba8024d`

Ran `scripts/test-ios-player-regressions.sh` against the dedicated `Nuvio Season Poster Review` iPhone 17 Pro simulator (`79C9658E-5D8B-4162-AACA-BC5A447D14E8`) while forcing a fresh execution of every `com.nuvio.app.features.details.*` test. The same 57 tests passed with zero failures, errors, or skips. The log contains known cryptography module-cache debug warnings, but no test or build failure.

Evidence: `build/season-poster-evidence/final-native-bba8024d/`.

### Full-distribution simulator build at `bba8024d`

Built the full iOS app with the normal `iosApp` scheme, Debug configuration, generic iOS Simulator destination, automatic package resolution disabled, and code signing disabled. Xcode 26.6 with the iOS 26.5 SDK completed successfully (`** BUILD SUCCEEDED **`, exit code 0). The resulting app is `build/ios-derived-season-poster/Build/Products/Debug-iphonesimulator/Nuvio.app`.

Evidence: `build/season-poster-evidence/final-build-bba8024d/`.

### Targeted synthetic visual check

Installed the committed build on the same dedicated simulator and configured only that simulator with a local synthetic add-on. The series fixture returns observed seasons 1, 2, and 3 plus `seasonPosters` containing a leading JSON null followed by distinct red, blue, and green artwork for seasons 1, 2, and 3. The detail screen visibly rendered exactly three cards in the correct order: red Season 1, blue Season 2, and green Season 3. Accessibility state identified only Season 1, Season 2, and Season 3 cards, and the local server recorded requests for `/season-1.svg`, `/season-2.svg`, and `/season-3.svg`.

Screenshot: `build/season-poster-evidence/synthetic-addon/final-season-posters.png`.

This is a narrow visual confirmation of the parser result reaching the season-card UI. It does not replace real-provider artwork, loading/caching, existing-device upgrade, or physical-device validation.

## Preservation and deferred checks

- The base-to-implementation diff contains only this review note, the four-line parser guard, and the 77-line regression test file. No UI, certification, player, account, download, signing, distribution, versioning, Watch Together, or unrelated parser file changed.
- Existing equal-count, explicit season 0, nonconsecutive-season, missing-metadata, absent-artwork, and ordinary one-based fallback behavior remains covered by the passing focused suites.
- A physical-device install was not performed. No TestFlight upload, release, merge, or auto-merge was performed.
- Real add-on artwork/loading behavior and migration from existing on-device data remain for review if maintainers want broader end-to-end coverage.
- PR #10's earlier deferrals are not treated as a blanket waiver. The U35 accessibility work and the native EOF/backward-seek concern are separate from this follow-up and are not claimed fixed here.
