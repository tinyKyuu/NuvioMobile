package com.nuvio.app.features.downloads

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSUserDefaults

internal actual object DownloadsLiveStatusPlatform {
    private const val notificationName = "NuvioDownloadsLiveStatusUpdated"
    private const val terminalNotificationName = "NuvioDownloadTerminalStatusUpdated"
    private const val userDefaultsPayloadKey = "nuvio.downloads.live_status.payload"
    private const val terminalPayloadKey = "nuvio.downloads.terminal_status.payload"
    private const val statusHistoryPayloadKey = "nuvio.downloads.status_history.payload"

    private val json = Json {
        encodeDefaults = true
    }

    private val stateLock = SynchronizedObject()
    private var lastPayload: String? = null
    private var lastStatusById = loadStatusHistory()

    actual fun onItemsChanged(items: List<DownloadItem>) = synchronized(stateLock) {
        publishTerminalTransitions(items)
        val liveItems = liveStatusItemsForDisplay(items)
        val primary = liveItems.firstOrNull()
        val additionalCount = (liveItems.size - 1).coerceAtLeast(0)

        val payload = primary?.let { item ->
            json.encodeToString(
                DownloadsLiveStatusPayload(
                    id = item.id,
                    title = item.title,
                    subtitle = item.displaySubtitle,
                    status = item.status.name,
                    downloadedBytes = item.downloadedBytes,
                    totalBytes = item.totalBytes,
                    queuedCount = additionalCount,
                    progressPercent = if (item.totalBytes != null && item.totalBytes > 0L) {
                        ((item.downloadedBytes.toDouble() / item.totalBytes.toDouble()) * 100.0)
                            .toInt()
                            .coerceIn(0, 100)
                    } else {
                        -1
                    },
                ),
            )
        }

        if (payload == lastPayload) return@synchronized
        lastPayload = payload

        val defaults = NSUserDefaults.standardUserDefaults
        if (payload == null) {
            defaults.removeObjectForKey(userDefaultsPayloadKey)
        } else {
            defaults.setObject(payload, forKey = userDefaultsPayloadKey)
        }

        NSNotificationCenter.defaultCenter.postNotificationName(notificationName, null)
    }

    private fun publishTerminalTransitions(items: List<DownloadItem>) {
        items.forEach { item ->
            val previous = lastStatusById[item.id] ?: return@forEach
            if (
                previous != item.status &&
                (item.status == DownloadStatus.Completed || item.status == DownloadStatus.Failed)
            ) {
                val payload = json.encodeToString(
                    DownloadsTerminalStatusPayload(
                        id = item.id,
                        title = item.title,
                        subtitle = item.displaySubtitle,
                        status = item.status.name,
                        message = item.errorMessage,
                    ),
                )
                NSUserDefaults.standardUserDefaults.setObject(payload, forKey = terminalPayloadKey)
                NSNotificationCenter.defaultCenter.postNotificationName(terminalNotificationName, null)
            }
        }
        lastStatusById = items.associate { it.id to it.status }
        val statusHistory = lastStatusById.map { (id, status) ->
            DownloadsPersistedStatus(id = id, status = status)
        }
        NSUserDefaults.standardUserDefaults.setObject(
            json.encodeToString(statusHistory),
            forKey = statusHistoryPayloadKey,
        )
    }

    private fun loadStatusHistory(): Map<String, DownloadStatus> {
        val payload = NSUserDefaults.standardUserDefaults
            .stringForKey(statusHistoryPayloadKey)
            ?.takeIf { it.isNotBlank() }
            ?: return emptyMap()
        return runCatching {
            json.decodeFromString<List<DownloadsPersistedStatus>>(payload)
                .associate { it.id to it.status }
        }.getOrDefault(emptyMap())
    }

}

@Serializable
private data class DownloadsLiveStatusPayload(
    val id: String,
    val title: String,
    val subtitle: String,
    val status: String,
    val downloadedBytes: Long,
    val totalBytes: Long? = null,
    val queuedCount: Int = 0,
    val progressPercent: Int,
)

@Serializable
private data class DownloadsTerminalStatusPayload(
    val id: String,
    val title: String,
    val subtitle: String,
    val status: String,
    val message: String? = null,
)

@Serializable
private data class DownloadsPersistedStatus(
    val id: String,
    val status: DownloadStatus,
)
