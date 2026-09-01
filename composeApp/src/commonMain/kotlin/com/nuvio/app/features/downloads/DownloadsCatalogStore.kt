package com.nuvio.app.features.downloads

import com.nuvio.app.features.downloads.db.Download_catalog_entry
import com.nuvio.app.features.downloads.db.DownloadsDatabase

internal interface DownloadsCatalogStore {
    fun allRecords(): List<DownloadRecord>

    fun recordsForProfile(ownerProfileKey: String): List<DownloadRecord>

    fun recordById(downloadId: String): DownloadRecord?

    fun commit(
        recordsToUpsert: Collection<DownloadRecord> = emptyList(),
        downloadIdsToDelete: Collection<String> = emptyList(),
    )

    fun replaceProfile(
        ownerProfileKey: String,
        records: Collection<DownloadRecord>,
    )
}

internal class SqlDownloadsCatalogStore(
    private val database: DownloadsDatabase,
) : DownloadsCatalogStore {
    private val queries = database.downloadCatalogQueries

    override fun allRecords(): List<DownloadRecord> =
        queries.selectAll()
            .executeAsList()
            .mapNotNull(Download_catalog_entry::toDomainRecord)

    override fun recordsForProfile(ownerProfileKey: String): List<DownloadRecord> =
        queries.selectForProfile(ownerProfileKey)
            .executeAsList()
            .mapNotNull(Download_catalog_entry::toDomainRecord)

    override fun recordById(downloadId: String): DownloadRecord? =
        queries.selectById(downloadId)
            .executeAsOneOrNull()
            ?.toDomainRecord()

    override fun commit(
        recordsToUpsert: Collection<DownloadRecord>,
        downloadIdsToDelete: Collection<String>,
    ) {
        database.transaction {
            downloadIdsToDelete.distinct().forEach(queries::deleteById)
            recordsToUpsert.forEach(::upsert)
        }
    }

    override fun replaceProfile(
        ownerProfileKey: String,
        records: Collection<DownloadRecord>,
    ) {
        database.transaction {
            queries.deleteForProfile(ownerProfileKey)
            records.forEach(::upsert)
        }
    }

    private fun upsert(record: DownloadRecord) {
        queries.upsert(
            download_id = record.downloadId,
            owner_profile_key = record.ownerProfileKey,
            record_version = record.recordVersion.toLong(),
            logical_content_key = record.logicalContentKey,
            transport_kind = record.transportKind.name,
            internal_state = record.internalState.name,
            state_reason = record.stateReason,
            relative_media_path = record.relativeMediaPath,
            platform_session_id = record.platformSessionId,
            platform_task_identifier = record.platformTaskIdentifier,
            last_event_id = record.lastEventId,
            item_payload = DownloadRecordCodec.encodeItem(record.item),
            downloaded_bytes = record.downloadedBytes,
            expected_bytes = record.expectedBytes,
            created_at_epoch_ms = record.createdAtEpochMs,
            started_at_epoch_ms = record.startedAtEpochMs,
            updated_at_epoch_ms = record.updatedAtEpochMs,
            completed_at_epoch_ms = record.completedAtEpochMs,
        )
    }
}

private fun Download_catalog_entry.toDomainRecord(): DownloadRecord? {
    val storedItem = DownloadRecordCodec.decodeItem(item_payload) ?: return null
    val transportKind = enumValueOrNull<DownloadTransportKind>(transport_kind)
        ?: DownloadTransportKind.DirectFile
    val internalState = enumValueOrNull<DownloadInternalState>(internal_state)
        ?: storedItem.status.toInternalState()
    return DownloadRecord(
        recordVersion = record_version.toInt(),
        downloadId = download_id,
        ownerProfileKey = owner_profile_key,
        logicalContentKey = logical_content_key,
        transportKind = transportKind,
        internalState = internalState,
        stateReason = state_reason,
        relativeMediaPath = relative_media_path,
        platformSessionId = platform_session_id,
        platformTaskIdentifier = platform_task_identifier,
        lastEventId = last_event_id,
        item = storedItem.copy(
            id = download_id,
            status = internalState.toVisibleStatus(),
            localFileUri = null,
            downloadedBytes = downloaded_bytes,
            totalBytes = expected_bytes,
            errorMessage = state_reason,
            createdAtEpochMs = created_at_epoch_ms,
            updatedAtEpochMs = updated_at_epoch_ms,
        ),
        downloadedBytes = downloaded_bytes,
        expectedBytes = expected_bytes,
        createdAtEpochMs = created_at_epoch_ms,
        startedAtEpochMs = started_at_epoch_ms,
        updatedAtEpochMs = updated_at_epoch_ms,
        completedAtEpochMs = completed_at_epoch_ms,
    )
}

private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
    enumValues<T>().firstOrNull { it.name == value }

internal object DownloadsCatalogStoreProvider {
    val store: DownloadsCatalogStore by lazy {
        SqlDownloadsCatalogStore(
            DownloadsDatabase(DownloadsDatabaseDriverFactory.createDriver()),
        )
    }
}
