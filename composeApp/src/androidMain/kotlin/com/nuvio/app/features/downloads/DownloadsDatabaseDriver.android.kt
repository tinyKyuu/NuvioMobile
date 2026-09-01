package com.nuvio.app.features.downloads

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.nuvio.app.features.downloads.db.DownloadsDatabase

internal actual object DownloadsDatabaseDriverFactory {
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    actual fun createDriver(): SqlDriver {
        val context = checkNotNull(appContext) {
            "Downloads database must be initialized before use"
        }
        return AndroidSqliteDriver(
            schema = DownloadsDatabase.Schema,
            context = context,
            name = "nuvio-downloads.db",
        )
    }
}
