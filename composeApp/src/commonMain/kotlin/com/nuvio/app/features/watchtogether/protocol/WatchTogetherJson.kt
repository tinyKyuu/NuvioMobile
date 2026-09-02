package com.nuvio.app.features.watchtogether.protocol

import kotlinx.serialization.json.Json

internal val WatchTogetherJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}
