<div align="center">

  <img src="https://nuvio.tv/assets/nuvio-app-logo-wordmark.webp" alt="Nuvio" width="320" />

  <p>
    A free, open-source media app for your phone, your desktop, and the TV you already own.
    <br />
    Bring your own sources. Nuvio turns them into a library with artwork, ratings, subtitles, and your place saved on every screen.
  </p>

  [Website](https://nuvio.tv) · [GitHub releases](https://github.com/NuvioMedia/NuvioMobile/releases/latest) · [Support Nuvio](https://nuvio.tv/support)

</div>

## Get Nuvio Mobile

- [Android on Google Play](https://play.google.com/store/apps/details?id=com.nuvio.app)
- [Android APK](https://github.com/NuvioMedia/NuvioMobile/releases/latest)
- iOS must be built from source.

## Build from source

```bash
git clone https://github.com/NuvioMedia/NuvioMobile.git
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

[GNU General Public License v3.0](./LICENSE)
