# F06 review: detail defaults and season selector cleanup

## Base and scope

- Branch: `codex/f06-detail-defaults-season-selector`
- Stable integration base: `dfffed43c583adfdf9095adb828ae62f88c21f2b`
- Target branch: `codex/testflight-internal`
- Review PR: [#25](https://github.com/tinyKyuu/NuvioMobile/pull/25)
- This is one review unit. Do not merge it before organizer approval.

F06 changes the default detail-page section order, migrates only the untouched
legacy order, makes Text the missing-value season-selector fallback, and removes
the repeated selected-season heading from multi-season content. It keeps the
existing Text chips, Posters view, episode cards, download and offline handling,
progress, and long-press actions.

## User-visible behavior

| Area | Before | After |
| --- | --- | --- |
| Detail section defaults | Episodes followed Trailers | Episodes follows Production |
| Existing detail layouts | No migration marker | Only the exact untouched legacy default migrates; custom order, visibility, and tab groups stay unchanged |
| Season selector | A missing stored value opened Posters | A missing stored value opens Text; saved Posters still opens Posters |
| Season heading | Multi-season screens repeated the selected season below the selector | Multi-season screens use the selector as the only season heading; single-season and non-series Videos headings remain |
| Toggle styling | Text and Posters used different container emphasis | Both modes use the same neutral container styling |

## Implementation notes

- The complete new default order is `ACTIONS`, `OVERVIEW`, `PRODUCTION`,
  `EPISODES`, `CAST`, `COMMENTS`, `TRAILERS`, `DETAILS`, `COLLECTION`,
  `MORE_LIKE_THIS`.
- Detail settings payloads now carry `section_order_migration_version = 1`.
- Migration requires all ten legacy keys with exact legacy order values, every
  section enabled, and no tab groups. Any custom order, disabled section, tab
  group, partial payload, or already-versioned payload keeps its stored choices.
- Fresh profiles and Reset normalize from the new order.
- A pulled pre-version payload is reapplied through the repository and then
  pushed back in versioned form. This prevents the old synced default from
  returning on a later pull.
- `SeasonViewModeStorage` remains profile-local and is still not part of profile
  settings sync. A missing or unparseable value falls back to Text. A saved
  Posters value remains Posters.
- The multi-season heading condition and neutral toggle styling are adapted from
  official Nuvio commit `c9f12a703c43a029b548c5bf7630c3dee6fbfa62`.
  The fork's episode rendering paths were left in place.
- The mode control now exposes a button role while retaining the same tap target,
  labels, layout, and persistence behavior.

## Validation completed

### Focused Android-host tests

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/muharrem/Library/Android/sdk' \
./gradlew -Pnuvio.ios.distribution=appstore -Pnuvio.android.distribution=full \
  :composeApp:testAndroidHostTest \
  --tests 'com.nuvio.app.features.details.MetaScreenSettingsMigrationTest' \
  --tests 'com.nuvio.app.features.details.SeasonViewModeTest' \
  --console=plain
```

Result: `BUILD SUCCESSFUL` in 53s.

The tests cover fresh defaults, exact legacy migration, custom order, disabled
and grouped section preservation, Reset defaults, malformed and partial payloads,
synced payload rewrite and reload, Text fallback, saved Posters, and single,
multi, and long season-count heading rules.

### Full host suite and Android builds

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/muharrem/Library/Android/sdk' \
./gradlew -Pnuvio.ios.distribution=appstore \
  -Pnuvio.android.distribution=playstore \
  :composeApp:testAndroidHostTest :androidApp:assembleDebug \
  --rerun-tasks --console=plain
```

Result: `BUILD SUCCESSFUL` in 1m 37s. The generated XML reports contain 978
tests with no failures, errors, or skips. Both APKs were produced:

- `androidApp-full-debug.apk`
- `androidApp-playstore-debug.apk`

The full APK was installed on a Pixel 8 API 36 emulator. It cold-started in
2.871s, remained alive, and emitted no fatal exception or ANR in the captured
startup log. The available Android emulator could run only headlessly, so this
is startup evidence rather than Android visual evidence.

### iOS compile and simulator build

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
./gradlew -Pnuvio.ios.distribution=appstore \
  -Pnuvio.android.distribution=playstore \
  :composeApp:compileKotlinIosSimulatorArm64 \
  --rerun-tasks --console=plain
```

Result: `BUILD SUCCESSFUL` in 42s.

```sh
NUVIO_IOS_DISTRIBUTION=appstore \
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
xcodebuild -project iosApp/iosApp.xcodeproj \
  -scheme iosApp -configuration Debug -sdk iphonesimulator \
  -destination 'id=0D237DF4-F2F0-4058-81B1-E7729A20535B' \
  -derivedDataPath /private/tmp/nuvio-f06-derived \
  -disableAutomaticPackageResolution build CODE_SIGNING_ALLOWED=NO
```

Result: `BUILD SUCCEEDED`. The pinned MPVKit submodule was initialized at
`d5cf091c80368bbbc1bbf2d195fbc55d926df888` before the successful build.

### Running-app migration and layout checks

- A fresh iPhone simulator profile persisted the new complete order and
  `section_order_migration_version = 1`. The Detail Page settings UI showed
  Production, Episodes, Cast, Comments, and Trailers in that order.
- Hiding Cast and choosing Reset to Default restored Cast to visible.
- An iPad simulator rendered the Detail Page settings at tablet width with the
  same default order.
- Seeding the iPad app container with the exact unversioned legacy default,
  then opening Detail Page settings, moved Episodes after Production and
  persisted migration version 1.
- Seeding an unversioned custom payload preserved Episodes-first custom order,
  hidden Cast, a Comments tab group, Tab Layout, and List episode cards. The
  payload then gained migration version 1.

## Deferred validation

- The available simulator profiles had no active add-ons or preserved offline
  titles. One-season, multi-season, Specials, and long-season-list detail
  screens could not be exercised end to end. The same limitation prevented
  manual checks of both episode-card paths, offline/download badges,
  Internet-required episodes, season long-press actions, and progress display.
- An authenticated sync profile was not available. The sync rewrite and
  idempotent reload path is covered by the focused test, but a live remote pull
  and multi-profile switch remain manual follow-up checks.
- No Android tablet AVD was available. Android phone coverage is limited to the
  headless startup check; iPhone and iPad settings layouts were inspected
  visually.
- Physical iPhone, iPad, Android phone, and Android tablet checks remain
  deferred.

Run `git diff --check` again after the final PR-link documentation update.
