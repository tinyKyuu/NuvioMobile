# Tracking provider build configuration

Trakt and Simkl are optional integrations. A build without their configuration
still runs, but Settings explains that tracking sign-in is unavailable and keeps
the connection actions disabled.

Register separate applications with each provider before producing a
tracking-enabled build. Do not reuse credentials from another application or
commit credentials to this repository.

## Redirect URLs

Use these exact redirect URLs in the provider applications:

- Trakt: `nuvio://auth/trakt`
- Simkl AUTH V2: `com.tinykyuu.nuvio://auth/simkl`

Both mobile hosts register the shared `nuvio` scheme used by Trakt and the
fork-specific `com.tinykyuu.nuvio` scheme used by Simkl. Other redirect URLs are
rejected unless the native host registration and callback routing are changed
deliberately.

## Simkl application registration

Create the Simkl application manually in the provider dashboard. Use:

- authorization version: AUTH V2;
- application type: Mobile, desktop & browser apps;
- callback URL: `com.tinykyuu.nuvio://auth/simkl`;
- homepage: `https://github.com/tinyKyuu/NuvioMobile`;
- a name and description that clearly identify this as an unofficial community
  fork.

Do not request or add a client secret. This is a public mobile client and uses
PKCE with the S256 challenge method. Once Simkl creates the application, copy
only its client ID into the ignored local configuration described below. The
client ID is intentionally embedded in a tracking-enabled app and is not an
authentication token, but this repository still keeps provider identifiers out
of tracked files and public build logs.

## Local configuration

Put provider values in the ignored root `local.properties` file:

```properties
TRAKT_CLIENT_ID=replace-with-your-trakt-client-id
TRAKT_CLIENT_SECRET=replace-with-your-trakt-client-secret
TRAKT_REDIRECT_URI=nuvio://auth/trakt
SIMKL_CLIENT_ID=replace-with-your-simkl-client-id
SIMKL_REDIRECT_URI=com.tinykyuu.nuvio://auth/simkl
SIMKL_APP_NAME=nuvio-watch-together
```

Trakt's authorization-code exchange currently requires both its client ID and
client secret. Simkl uses AUTH V2 with PKCE and requires only its public client
ID. The app requests `media:read media:write`, stores each user's access and
refresh tokens in iOS Keychain or Android encrypted preferences, refreshes the
access token before expiry or after one authenticated `401`, and revokes the
current grant when the user disconnects. These values may also be supplied
through environment variables with the same names; local properties take
precedence.

Before archiving a tracking-enabled build, run:

```bash
./gradlew :composeApp:verifyTrackingConfiguration
```

The task reports only whether required values and supported redirects are
present. It never prints configured values. Release workflows run the same
check after restoring their private runtime properties.

## Public repository boundary

Keep client IDs, client secrets, access tokens, refresh tokens, provider account
details, and callback logs out of tracked files, commits, issues, pull requests,
and uploaded artifacts. A Simkl client ID alone does not grant access to an
account. Access and refresh tokens do, so revoke the affected Simkl grant if
either token is exposed. Rotate any exposed provider secret before continuing.
