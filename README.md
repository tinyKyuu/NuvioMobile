<div align="center">

  <h1>Nuvio Internal</h1>

  <p>
    An unofficial personal iOS TestFlight fork of NuvioMobile.
  </p>

  [Upstream project](https://github.com/NuvioMedia/NuvioMobile) · [Official website](https://nuvio.tv) · [Support upstream](https://nuvio.tv/support)

</div>

> [!IMPORTANT]
> tinyKyuu modified this fork on August 30, 2026, for internal iOS testing.
> It is not affiliated with or endorsed by NuvioMedia. The source and
> modifications are released under the GNU General Public License v3.0.

## Get the upstream Nuvio Mobile app

- [Android on Google Play](https://play.google.com/store/apps/details?id=com.nuvio.app)
- [Android APK](https://github.com/NuvioMedia/NuvioMobile/releases/latest)
- The upstream iOS app must be built from source.

## Build from source

```bash
git clone --branch codex/testflight-internal https://github.com/tinyKyuu/NuvioMobile.git
cd NuvioMobile
```

### Android

Android development requires Android Studio and the Android SDK.

```bash
./gradlew :androidApp:assembleFullDebug
```

### iOS

iOS development requires macOS and Xcode.

For a full iOS build that can use the official Nuvio account service, configure
the public server settings locally first. The generated `local.properties` file
is ignored by Git.

```bash
./scripts/configure-official-nuvio-server.sh
./scripts/prepare-ios-dependencies.sh
```

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

For this fork's internal TestFlight build, copy
`iosApp/Configuration/Signing.example.xcconfig` to the ignored
`Signing.local.xcconfig`, enter the Apple Developer Team ID shown by Xcode, and
run:

```bash
./scripts/archive-ios-testflight.sh
./scripts/archive-ios-testflight.sh --upload
```

The export is marked **TestFlight Internal Only**. It cannot be promoted to
external testing or released on the App Store.

The shared app is built with Kotlin Multiplatform and Compose Multiplatform.

## License

This fork and its modifications are released under the
[GNU General Public License v3.0](./LICENSE). Copyright and attribution notices
from the upstream project remain in effect.
