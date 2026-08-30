+# Internal TestFlight notes

This fork keeps the App Store Connect product name separate from the installed
app name. The product can be named **Nuvio Internal** in App Store Connect while
`CFBundleDisplayName` remains **Nuvio** on a device.

## Why an uploaded build might not appear

A completed upload does not guarantee that a build will appear in TestFlight.
App Store Connect performs delivery processing first. If that processing fails,
the upload can have a failed delivery record without creating a visible
TestFlight build.

One earlier iOS implementation manually declared the following CommonCrypto
AES-GCM symbols:

- `CCCryptorGCMAddAAD`
- `CCCryptorGCMDecrypt`
- `CCCryptorGCMEncrypt`
- `CCCryptorGCMFinal`

Those symbols are not part of the public iOS SDK API. App Store processing can
therefore reject a binary that links them as non-public API usage.

The iOS plugin bridge now uses Apple's public
[CryptoKit AES-GCM API](https://developer.apple.com/documentation/cryptokit/aes/gcm).
It preserves the existing plugin data format: ciphertext followed by a 16-byte
authentication tag. No plugin, P2P, download, account, or server feature is
removed by this change. CryptoKit requires an AES-GCM nonce of at least 12
bytes, which covers the standard 12-byte nonce used by Web Crypto clients.

The archive script scans the final app executable for the unsupported symbols
and stops before export or upload if any return.

## Verify the AES-GCM bridge

Compile and run the known-answer test on macOS:

```bash
xcrun swiftc \
  iosApp/iosApp/PluginCryptoBridge.swift \
  scripts/plugin-crypto-bridge-self-test/main.swift \
  -o /tmp/nuvio-plugin-crypto-self-test
/tmp/nuvio-plugin-crypto-self-test
```

The test checks a standard AES-GCM vector, a complete encrypt/decrypt round
trip, authentication-tag rejection, and the minimum nonce length.

## Public repository boundary

It is appropriate to publish the source fix, the unsupported symbol names, the
test, and this troubleshooting explanation. Do not commit account-specific or
secret material, including:

- Apple ID email addresses or App Store Connect delivery records
- App Store Connect API private keys, issuer IDs, or key IDs
- signing certificates, provisioning profiles, or private keys
- Apple team identifiers stored for personal signing
- server administrative or service-role credentials

This repository intentionally ignores `local.properties` and
`iosApp/Configuration/Signing.local.xcconfig`. Keep official server runtime
configuration and personal Apple signing configuration in those local files.
