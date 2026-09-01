package com.nuvio.app.features.downloads

import app.cash.sqldelight.db.SqlDriver

internal expect object DownloadsDatabaseDriverFactory {
    fun createDriver(): SqlDriver
}
