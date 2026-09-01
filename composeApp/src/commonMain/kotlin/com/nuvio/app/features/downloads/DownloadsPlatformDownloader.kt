package com.nuvio.app.features.downloads

internal data class DownloadPlatformRequest(
    val downloadId: String,
    val ownerProfileKey: String,
    val sourceUrl: String,
    val sourceHeaders: Map<String, String>,
    val destinationFileName: String,
    val resumeDownloadedBytes: Long = 0L,
    val networkPolicy: DownloadNetworkPolicy = DownloadNetworkPolicy(),
)

internal enum class DownloadPlatformTaskState {
    Running,
    Suspended,
    Canceling,
    Completed,
}

internal data class DownloadPlatformTaskSnapshot(
    val downloadId: String,
    val sessionIdentifier: String,
    val taskIdentifier: Long,
    val state: DownloadPlatformTaskState,
    val downloadedBytes: Long,
    val totalBytes: Long?,
)

internal interface DownloadsTaskHandle {
    fun pause()

    fun cancel()
}

internal expect object DownloadsPlatformDownloader {
    val supportsPersistentBackgroundTransfers: Boolean

    fun initialize()

    fun start(
        request: DownloadPlatformRequest,
        onTaskCreated: (sessionIdentifier: String?, taskIdentifier: Long?) -> Unit,
        onWaitingForConnectivity: () -> Unit,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onFinalizing: () -> Unit,
        onSuccess: (localFileUri: String, relativeMediaPath: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
    ): DownloadsTaskHandle

    fun pause(downloadId: String)

    fun cancel(downloadId: String)

    fun removeFile(localFileUri: String?): Boolean

    fun removePartialFile(downloadId: String, destinationFileName: String): Boolean

    fun resolveLocalFileUri(localFileUri: String?, relativeMediaPath: String): String?

    fun openDownloadsDirectory(): Boolean

    fun exportFile(localFileUri: String): Boolean
}
