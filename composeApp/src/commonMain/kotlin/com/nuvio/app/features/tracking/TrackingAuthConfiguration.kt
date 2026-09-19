package com.nuvio.app.features.tracking

internal enum class TrackingAuthConfigurationStatus {
    READY,
    MISSING_REQUIRED_VALUES,
    UNSUPPORTED_REDIRECT_URI,
}

internal fun trackingAuthConfigurationStatus(
    requiredValues: Iterable<String>,
    redirectUri: String,
    supportedRedirectUri: String,
): TrackingAuthConfigurationStatus = when {
    requiredValues.any { it.isBlank() } ->
        TrackingAuthConfigurationStatus.MISSING_REQUIRED_VALUES
    !redirectUri.equals(supportedRedirectUri, ignoreCase = true) ->
        TrackingAuthConfigurationStatus.UNSUPPORTED_REDIRECT_URI
    else -> TrackingAuthConfigurationStatus.READY
}
