package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val DOWNLOAD_RECORD_VERSION = 3

internal fun downloadOwnerProfileKey(profileId: Int): String = "profile:$profileId"

internal enum class DownloadTransportKind {
    DirectFile,
}

internal enum class DownloadInternalState {
    Queued,
    Downloading,
    WaitingForNetwork,
    Paused,
    Finalizing,
    Completed,
    FailedRecoverable,
    FailedPermanent,
}

internal data class DownloadRecord(
    val recordVersion: Int = DOWNLOAD_RECORD_VERSION,
    val downloadId: String,
    val ownerProfileKey: String,
    val logicalContentKey: String,
    val transportKind: DownloadTransportKind = DownloadTransportKind.DirectFile,
    val internalState: DownloadInternalState,
    val stateReason: String? = null,
    val relativeMediaPath: String? = null,
    val platformSessionId: String? = null,
    val platformTaskIdentifier: Long? = null,
    val lastEventId: String? = null,
    val item: DownloadItem,
    val downloadedBytes: Long = item.downloadedBytes,
    val expectedBytes: Long? = item.totalBytes,
    val createdAtEpochMs: Long = item.createdAtEpochMs,
    val startedAtEpochMs: Long? = null,
    val updatedAtEpochMs: Long = item.updatedAtEpochMs,
    val completedAtEpochMs: Long? = null,
)

internal fun DownloadItem.toDownloadRecord(
    ownerProfileKey: String,
    nowEpochMs: Long = updatedAtEpochMs,
): DownloadRecord {
    val internalState = status.toInternalState()
    return DownloadRecord(
        downloadId = id,
        ownerProfileKey = ownerProfileKey,
        logicalContentKey = logicalContentKey,
        internalState = internalState,
        stateReason = errorMessage?.takeIf { it.isNotBlank() },
        relativeMediaPath = localFileUri?.takeIf { it.isNotBlank() }?.let { fileName },
        item = this,
        startedAtEpochMs = nowEpochMs.takeIf {
            internalState == DownloadInternalState.Downloading ||
                internalState == DownloadInternalState.WaitingForNetwork ||
                internalState == DownloadInternalState.Finalizing
        },
        completedAtEpochMs = nowEpochMs.takeIf { internalState == DownloadInternalState.Completed },
    )
}

internal fun migrateLegacyDownloadItems(
    items: Collection<DownloadItem>,
    ownerProfileKey: String,
    resolveLocalFileUri: DownloadFileResolver,
): List<DownloadRecord> = items.map { legacyItem ->
    legacyItem.toDownloadRecord(ownerProfileKey)
        .normalizeForColdLaunch(resolveLocalFileUri)
}

internal fun DownloadStatus.toInternalState(): DownloadInternalState = when (this) {
    DownloadStatus.Queued -> DownloadInternalState.Queued
    DownloadStatus.Downloading -> DownloadInternalState.Downloading
    DownloadStatus.WaitingForNetwork -> DownloadInternalState.WaitingForNetwork
    DownloadStatus.Paused -> DownloadInternalState.Paused
    DownloadStatus.Finalizing -> DownloadInternalState.Finalizing
    DownloadStatus.Completed -> DownloadInternalState.Completed
    DownloadStatus.Failed -> DownloadInternalState.FailedRecoverable
}

internal fun DownloadInternalState.toVisibleStatus(): DownloadStatus = when (this) {
    DownloadInternalState.Queued -> DownloadStatus.Queued
    DownloadInternalState.Downloading -> DownloadStatus.Downloading
    DownloadInternalState.WaitingForNetwork -> DownloadStatus.WaitingForNetwork
    DownloadInternalState.Paused -> DownloadStatus.Paused
    DownloadInternalState.Finalizing -> DownloadStatus.Finalizing
    DownloadInternalState.Completed -> DownloadStatus.Completed
    DownloadInternalState.FailedRecoverable,
    DownloadInternalState.FailedPermanent,
    -> DownloadStatus.Failed
}

internal fun DownloadRecord.withRuntimeItem(item: DownloadItem): DownloadRecord = copy(
    item = item.copy(
        id = downloadId,
        status = internalState.toVisibleStatus(),
        downloadedBytes = downloadedBytes,
        totalBytes = expectedBytes,
        errorMessage = stateReason,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
    ),
)

internal fun DownloadRecord.migrateToCurrentVersion(): DownloadRecord {
    var migrated = this
    if (migrated.recordVersion < 1) {
        migrated = migrated.copy(
            recordVersion = 1,
            logicalContentKey = migrated.item.logicalContentKey,
            relativeMediaPath = migrated.relativeMediaPath
                ?: migrated.item.localFileUri?.takeIf { it.isNotBlank() }?.let { migrated.item.fileName },
        )
    }
    if (migrated.recordVersion < 2) {
        migrated = migrated.copy(
            recordVersion = 2,
            item = migrated.item.copy(providerAddonId = null),
        )
    }
    if (migrated.recordVersion < 3) {
        migrated = migrated.copy(recordVersion = 3)
    }
    return migrated
}

internal fun DownloadRecord.hasSameDurableContents(other: DownloadRecord): Boolean =
    withoutRuntimeRequest() == other.withoutRuntimeRequest()

private fun DownloadRecord.withoutRuntimeRequest(): DownloadRecord = copy(
    item = item.copy(
        providerAddonId = null,
        sourceUrl = "",
        sourceHeaders = emptyMap(),
        sourceResponseHeaders = emptyMap(),
        localFileUri = null,
    ),
)

internal fun DownloadRecord.normalizeForColdLaunch(
    resolveLocalFileUri: DownloadFileResolver,
    preservePlatformActiveState: Boolean = false,
): DownloadRecord {
    val migrated = migrateToCurrentVersion()
    val normalizedState = when (migrated.internalState) {
        DownloadInternalState.Downloading,
        DownloadInternalState.WaitingForNetwork,
        DownloadInternalState.Finalizing,
        -> if (preservePlatformActiveState) migrated.internalState else DownloadInternalState.Paused

        else -> migrated.internalState
    }
    val normalizedReason = if (normalizedState == DownloadInternalState.Paused) null else migrated.stateReason
    val relativeMediaPath = migrated.relativeMediaPath
        ?.takeIf { it.isNotBlank() }
        ?: migrated.item.fileName
    val resolvedLocalFileUri = if (normalizedState == DownloadInternalState.Completed) {
        resolveLocalFileUri(migrated.item.localFileUri, relativeMediaPath)
    } else {
        null
    }
    val normalizedItem = migrated.item.copy(
        localFileUri = resolvedLocalFileUri,
        status = normalizedState.toVisibleStatus(),
        downloadedBytes = migrated.downloadedBytes,
        totalBytes = migrated.expectedBytes,
        errorMessage = normalizedReason,
        createdAtEpochMs = migrated.createdAtEpochMs,
        updatedAtEpochMs = migrated.updatedAtEpochMs,
    )
    return migrated.copy(
        recordVersion = DOWNLOAD_RECORD_VERSION,
        internalState = normalizedState,
        stateReason = normalizedReason,
        relativeMediaPath = migrated.relativeMediaPath
            ?: resolvedLocalFileUri?.let { migrated.item.fileName },
        item = normalizedItem,
    )
}

@Serializable
private data class StoredDownloadItem(
    val item: DownloadItem,
)

internal object DownloadRecordCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeItem(item: DownloadItem): String = json.encodeToString(
        StoredDownloadItem(
            item = item.copy(
                providerAddonId = null,
                sourceUrl = "",
                sourceHeaders = emptyMap(),
                sourceResponseHeaders = emptyMap(),
                localFileUri = null,
            ),
        ),
    )

    fun decodeItem(payload: String): DownloadItem? = runCatching {
        json.decodeFromString<StoredDownloadItem>(payload).item
    }.getOrNull()
}

@Serializable
private data class StoredDownloadsPayload(
    val items: List<DownloadItem> = emptyList(),
)

internal object DownloadsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decodeItemsOrNull(payload: String): List<DownloadItem>? = runCatching {
        json.decodeFromString<StoredDownloadsPayload>(payload).items
    }.getOrNull()

    fun decodeItems(payload: String): List<DownloadItem> = decodeItemsOrNull(payload).orEmpty()

    fun encodeItems(items: Collection<DownloadItem>): String = json.encodeToString(
        StoredDownloadsPayload(
            items = items.toList(),
        ),
    )
}
