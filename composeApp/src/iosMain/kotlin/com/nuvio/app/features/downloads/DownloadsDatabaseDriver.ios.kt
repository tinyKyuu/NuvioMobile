package com.nuvio.app.features.downloads

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.nuvio.app.features.downloads.db.DownloadsDatabase

internal actual object DownloadsDatabaseDriverFactory {
    actual fun createDriver(): SqlDriver = NativeSqliteDriver(
        schema = DownloadsDatabase.Schema,
        name = "nuvio-downloads.db",
    )
}
