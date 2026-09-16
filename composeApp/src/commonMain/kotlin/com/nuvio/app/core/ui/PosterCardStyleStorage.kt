package com.nuvio.app.core.ui

internal expect object PosterCardStyleStorage {
    fun loadProfilePayload(): String?
    fun saveProfilePayload(payload: String)
    fun loadLocalSizePayload(): String?
    fun saveLocalSizePayload(payload: String)
}

internal expect fun isTabletFormFactor(): Boolean
