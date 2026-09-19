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
- Simkl: `nuvio://auth/simkl`

The `nuvio` URL scheme is registered by both mobile hosts. Other redirect URLs
are rejected by the app unless the corresponding native host registration and
callback routing are changed deliberately.

## Local configuration

Put provider values in the ignored root `local.properties` file:

```properties
TRAKT_CLIENT_ID=replace-with-your-trakt-client-id
TRAKT_CLIENT_SECRET=replace-with-your-trakt-client-secret
TRAKT_REDIRECT_URI=nuvio://auth/trakt
SIMKL_CLIENT_ID=replace-with-your-simkl-client-id
SIMKL_REDIRECT_URI=nuvio://auth/simkl
SIMKL_APP_NAME=nuvio
```

Trakt's authorization-code exchange currently requires both its client ID and
client secret. Simkl uses PKCE and requires only its client ID. These values may
also be supplied through environment variables with the same names; local
properties take precedence.

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
and uploaded artifacts. If a value is exposed, stop using it and rotate it
through the provider before continuing.
