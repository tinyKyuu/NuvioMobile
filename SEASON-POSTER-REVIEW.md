# Leading null Specials poster follow-up

Base: `9f7bc7a320abccd6252ff63c2193419bad8db633`

Branch: `codex/season-poster-null-specials`

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

Final host, Kotlin/Native, build, and manual-validation results pending.
