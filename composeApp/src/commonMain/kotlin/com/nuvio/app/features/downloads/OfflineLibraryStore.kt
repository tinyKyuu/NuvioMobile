package com.nuvio.app.features.downloads

import com.nuvio.app.features.downloads.db.DownloadsDatabase
import com.nuvio.app.features.downloads.db.Offline_title_record
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal interface OfflineLibraryStore {
    fun recordsForProfile(ownerProfileKey: String): List<OfflineTitleRecord>
    fun allRecords(): List<OfflineTitleRecord>
    fun record(key: String): OfflineTitleRecord?
    fun commit(
        recordsToUpsert: Collection<OfflineTitleRecord> = emptyList(),
        keysToDelete: Collection<String> = emptyList(),
    )
    fun deleteProfile(ownerProfileKey: String)
}

internal class SqlOfflineLibraryStore(
    private val database: DownloadsDatabase,
) : OfflineLibraryStore {
    private val queries = database.downloadCatalogQueries

    override fun recordsForProfile(ownerProfileKey: String): List<OfflineTitleRecord> =
        queries.selectOfflineTitlesForProfile(ownerProfileKey)
            .executeAsList()
            .mapNotNull(Offline_title_record::toDomainRecord)

    override fun allRecords(): List<OfflineTitleRecord> =
        queries.selectAllOfflineTitles()
            .executeAsList()
            .mapNotNull(Offline_title_record::toDomainRecord)

    override fun record(key: String): OfflineTitleRecord? =
        queries.selectOfflineTitleByKey(key)
            .executeAsOneOrNull()
            ?.toDomainRecord()

    override fun commit(
        recordsToUpsert: Collection<OfflineTitleRecord>,
        keysToDelete: Collection<String>,
    ) {
        database.transaction {
            keysToDelete.distinct().forEach(queries::deleteOfflineTitleByKey)
            recordsToUpsert.forEach { record ->
                queries.upsertOfflineTitle(
                    record_key = record.key,
                    owner_profile_key = record.ownerProfileKey,
                    meta_type = record.metaType,
                    meta_id = record.metaId,
                    record_version = record.recordVersion.toLong(),
                    generation = record.generation,
                    payload = OfflineTitleRecordCodec.encode(record),
                    created_at_epoch_ms = record.createdAtEpochMs,
                    updated_at_epoch_ms = record.updatedAtEpochMs,
                )
            }
        }
    }

    override fun deleteProfile(ownerProfileKey: String) {
        queries.deleteOfflineTitlesForProfile(ownerProfileKey)
    }
}

private fun Offline_title_record.toDomainRecord(): OfflineTitleRecord? =
    OfflineTitleRecordCodec.decode(payload)?.copy(
        recordVersion = record_version.toInt(),
        key = record_key,
        ownerProfileKey = owner_profile_key,
        metaType = meta_type,
        metaId = meta_id,
        generation = generation,
        createdAtEpochMs = created_at_epoch_ms,
        updatedAtEpochMs = updated_at_epoch_ms,
    )

internal object OfflineTitleRecordCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(record: OfflineTitleRecord): String = json.encodeToString(record)

    fun decode(payload: String): OfflineTitleRecord? = runCatching {
        json.decodeFromString<OfflineTitleRecord>(payload)
    }.getOrNull()
}

internal object OfflineLibraryStoreProvider {
    val store: OfflineLibraryStore by lazy {
        SqlOfflineLibraryStore(DownloadsCatalogStoreProvider.database)
    }
}
