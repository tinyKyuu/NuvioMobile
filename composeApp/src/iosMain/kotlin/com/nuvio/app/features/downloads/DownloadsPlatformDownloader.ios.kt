package com.nuvio.app.features.downloads

import co.touchlab.kermit.Logger
import com.nuvio.app.features.streams.StreamItem
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.download_failed
import nuvio.composeapp.generated.resources.downloads_error_finalize_file_failed
import nuvio.composeapp.generated.resources.network_request_failed_http
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDownloadDelegateProtocol
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionDownloadTaskResumeData
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskStateCanceling
import platform.Foundation.NSURLSessionTaskStateCompleted
import platform.Foundation.NSURLSessionTaskStateRunning
import platform.Foundation.NSURLSessionTaskStateSuspended
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.UIKit.UIApplication
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController
import platform.darwin.NSObject
import platform.posix.getenv

private const val DOWNLOAD_REQUEST_TIMEOUT_SECONDS = 60.0
private const val DOWNLOAD_RESOURCE_TIMEOUT_SECONDS = 7.0 * 24.0 * 60.0 * 60.0
private const val TASK_DESCRIPTION_VERSION = "nuvio-v1"
private const val CANNOT_RESUME_ERROR_CODE = -1007L
private const val RESUME_CHECKPOINT_DEFAULTS_PREFIX = "nuvio.download.resume-checkpoint."
private const val RESUME_CHECKPOINT_TOLERANCE_BYTES = 64L * 1024L * 1024L

private val downloadsTransportLog = Logger.withTag("DownloadsTransport")
private val backgroundDownloadManager = IosBackgroundDownloadManager()

fun initializeDownloadsBackgroundTransfers() {
    DownloadsPlatformDownloader.initialize()
    IosBackgroundDownloadSmokeHarness.startIfRequested()
}

fun handleDownloadsBackgroundEvents(
    identifier: String,
    completionHandler: () -> Unit,
) {
    DownloadsPlatformDownloader.initialize()
    backgroundDownloadManager.handleBackgroundEvents(identifier, completionHandler)
}

@OptIn(ExperimentalForeignApi::class)
internal actual object DownloadsPlatformDownloader {
    actual val supportsPersistentBackgroundTransfers: Boolean = true

    actual fun initialize() {
        backgroundDownloadManager.initialize()
    }

    actual fun start(
        request: DownloadPlatformRequest,
        onTaskCreated: (sessionIdentifier: String?, taskIdentifier: Long?) -> Unit,
        onWaitingForConnectivity: () -> Unit,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onFinalizing: () -> Unit,
        onSuccess: (localFileUri: String, relativeMediaPath: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
    ): DownloadsTaskHandle = backgroundDownloadManager.start(
        request = request,
        callbacks = IosDownloadCallbacks(
            onTaskCreated = onTaskCreated,
            onWaitingForConnectivity = onWaitingForConnectivity,
            onProgress = onProgress,
            onFinalizing = onFinalizing,
            onSuccess = onSuccess,
            onFailure = onFailure,
        ),
    )

    actual fun pause(downloadId: String) {
        backgroundDownloadManager.pause(downloadId)
    }

    actual fun cancel(downloadId: String) {
        backgroundDownloadManager.cancel(downloadId)
    }

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        val path = localFileUri.toLocalPath() ?: return false
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
            return removePathIfExists(path)
        }

        val fileName = path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return false
        return removePathIfExists("${legacyDownloadsDirectoryPath()}/$fileName")
    }

    actual fun removePartialFile(downloadId: String, destinationFileName: String): Boolean {
        backgroundDownloadManager.removeResumeData(downloadId)
        return removePathIfExists("${legacyDownloadsDirectoryPath()}/$destinationFileName.part")
    }

    actual fun resolveLocalFileUri(localFileUri: String?, relativeMediaPath: String): String? {
        localFileUri?.toLocalPath()
            ?.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
            ?.let(::fileUriForPath)
            ?.let { return it }

        val normalizedRelativePath = relativeMediaPath.normalizedRelativeMediaPath()
        if (normalizedRelativePath != null) {
            val currentPath = "${applicationSupportDownloadsRoot()}/$normalizedRelativePath"
            if (NSFileManager.defaultManager.fileExistsAtPath(currentPath)) {
                return fileUriForPath(currentPath)
            }
        }

        val fileName = normalizedRelativePath
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: localFileUri?.toLocalPath()?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: return null
        val legacyPath = "${legacyDownloadsDirectoryPath()}/$fileName"
        return legacyPath
            .takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
            ?.let(::fileUriForPath)
    }

    actual fun openDownloadsDirectory(): Boolean {
        val url = NSURL.fileURLWithPath(legacyDownloadsDirectoryPath())
        UIApplication.sharedApplication.openURL(
            url = url,
            options = emptyMap<Any?, Any>(),
            completionHandler = null,
        )
        return true
    }

    actual fun exportFile(localFileUri: String): Boolean {
        val path = localFileUri.toLocalPath()
            ?.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
            ?: return false
        val presenter = UIApplication.sharedApplication.connectedScenes
            .filterIsInstance<UIWindowScene>()
            .asSequence()
            .flatMap { it.windows.filterIsInstance<UIWindow>().asSequence() }
            .firstOrNull { it.isKeyWindow() }
            ?.rootViewController
            ?.topPresentedViewController()
            ?: return false
        val controller = UIActivityViewController(
            activityItems = listOf(NSURL.fileURLWithPath(path)),
            applicationActivities = null,
        )
        controller.popoverPresentationController?.sourceView = presenter.view
        controller.popoverPresentationController?.sourceRect = presenter.view.bounds
        presenter.presentViewController(controller, animated = true, completion = null)
        return true
    }
}

private fun UIViewController.topPresentedViewController(): UIViewController {
    var current = this
    while (current.presentedViewController != null) {
        current = current.presentedViewController ?: break
    }
    return current
}

private data class IosDownloadCallbacks(
    val onTaskCreated: (sessionIdentifier: String?, taskIdentifier: Long?) -> Unit,
    val onWaitingForConnectivity: () -> Unit,
    val onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    val onFinalizing: () -> Unit,
    val onSuccess: (localFileUri: String, relativeMediaPath: String, totalBytes: Long?) -> Unit,
    val onFailure: (message: String) -> Unit,
)

@OptIn(ExperimentalForeignApi::class)
private object IosBackgroundDownloadSmokeHarness {
    private val lock = SynchronizedObject()
    private var started = false

    fun startIfRequested() {
        val sourceUrl = getenv("NUVIO_DOWNLOAD_BACKGROUND_SMOKE_URL")
            ?.toKString()
            ?.trim()
            ?.takeIf { it.startsWith("http://127.0.0.1:") }
            ?: return
        val smokeId = getenv("NUVIO_DOWNLOAD_BACKGROUND_SMOKE_ID")
            ?.toKString()
            ?.trim()
            ?.safePathComponent()
            ?.takeIf { it.isNotBlank() }
            ?: "phase2-background-smoke"
        val smokeCount = getenv("NUVIO_DOWNLOAD_BACKGROUND_SMOKE_COUNT")
            ?.toKString()
            ?.toIntOrNull()
            ?.coerceIn(1, 3)
            ?: 1
        val shouldStart = synchronized(lock) {
            if (started) false else true.also { started = true }
        }
        if (!shouldStart) return

        downloadsTransportLog.i {
            "event=signed_app_smoke_start item_count=$smokeCount"
        }
        repeat(smokeCount) { index ->
            val indexedId = if (smokeCount == 1) smokeId else "$smokeId-${index + 1}"
            val result = DownloadsRepository.enqueueFromStream(
                contentType = "movie",
                videoId = indexedId,
                parentMetaId = indexedId,
                parentMetaType = "movie",
                title = "Phase 3 Queue Smoke Test ${index + 1}",
                logo = null,
                poster = null,
                background = null,
                seasonNumber = null,
                episodeNumber = null,
                episodeTitle = null,
                episodeThumbnail = null,
                stream = StreamItem(
                    name = "Local direct-file fixture",
                    url = sourceUrl,
                    addonName = "Nuvio test fixture",
                    addonId = "test:phase3-queue",
                ),
            )
            downloadsTransportLog.i {
                "event=signed_app_smoke_enqueued download_id=$indexedId result=$result"
            }
        }
    }
}

private data class IosTaskMetadata(
    val downloadId: String,
    val ownerProfileKey: String,
    val destinationFileName: String,
) {
    val relativeMediaPath: String
        get() = listOf(
            ownerProfileKey.safePathComponent(),
            downloadId.safePathComponent(),
            destinationFileName.safeFileName(),
        ).joinToString("/")

    fun encode(): String = listOf(
        TASK_DESCRIPTION_VERSION,
        downloadId,
        ownerProfileKey,
        destinationFileName,
    ).joinToString("\t")

    companion object {
        fun decode(value: String?): IosTaskMetadata? {
            val parts = value?.split('\t', limit = 4) ?: return null
            if (parts.size != 4 || parts[0] != TASK_DESCRIPTION_VERSION) return null
            val downloadId = parts[1].trim().takeIf { it.isNotBlank() } ?: return null
            val ownerProfileKey = parts[2].trim().takeIf { it.isNotBlank() } ?: return null
            val destinationFileName = parts[3].safeFileName().takeIf { it.isNotBlank() } ?: return null
            return IosTaskMetadata(downloadId, ownerProfileKey, destinationFileName)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosBackgroundDownloadManager : NSObject(), NSURLSessionDownloadDelegateProtocol {
    private val stateLock = SynchronizedObject()
    private val callbacksByDownloadId = mutableMapOf<String, IosDownloadCallbacks>()
    private val tasksByDownloadId = mutableMapOf<String, NSURLSessionDownloadTask>()
    private val pauseRequestedIds = mutableSetOf<String>()
    private val terminalHandledIds = mutableSetOf<String>()
    private val backgroundCompletionHandlers = mutableMapOf<String, () -> Unit>()
    private var initialized = false

    private val isStandaloneTestProcess: Boolean
        get() = getenv("NUVIO_DOWNLOAD_TEST_BASE_URL") != null

    private val sessionIdentifier: String by lazy {
        val bundleIdentifier = NSBundle.mainBundle.bundleIdentifier
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "com.nuvio.ios"
        "$bundleIdentifier.downloads.background.v1"
    }

    private val session: NSURLSession by lazy {
        val configuration = if (isStandaloneTestProcess) {
            NSURLSessionConfiguration.defaultSessionConfiguration()
        } else {
            NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(sessionIdentifier)
        }.apply {
            timeoutIntervalForRequest = DOWNLOAD_REQUEST_TIMEOUT_SECONDS
            timeoutIntervalForResource = DOWNLOAD_RESOURCE_TIMEOUT_SECONDS
            waitsForConnectivity = true
            allowsCellularAccess = true
            allowsExpensiveNetworkAccess = true
            allowsConstrainedNetworkAccess = true
            if (!isStandaloneTestProcess) {
                sessionSendsLaunchEvents = true
                discretionary = false
            }
        }
        NSURLSession.sessionWithConfiguration(
            configuration = configuration,
            delegate = this,
            delegateQueue = NSOperationQueue().apply {
                maxConcurrentOperationCount = 1
            },
        )
    }

    fun initialize() {
        val shouldReconcile = synchronized(stateLock) {
            if (initialized) {
                false
            } else {
                initialized = true
                true
            }
        }
        session
        applicationSupportDownloadsRoot()
        if (shouldReconcile && !isStandaloneTestProcess) {
            reconcileSystemTasks()
        }
    }

    fun handleBackgroundEvents(identifier: String, completionHandler: () -> Unit) {
        if (identifier != sessionIdentifier) {
            downloadsTransportLog.w {
                "event=background_events_ignored session_id=$identifier reason=unknown_session"
            }
            NSOperationQueue.mainQueue.addOperationWithBlock(completionHandler)
            return
        }
        synchronized(stateLock) {
            backgroundCompletionHandlers[identifier] = completionHandler
        }
        downloadsTransportLog.i {
            "event=background_events_received session_id=$identifier"
        }
        initialize()
    }

    fun start(
        request: DownloadPlatformRequest,
        callbacks: IosDownloadCallbacks,
    ): DownloadsTaskHandle {
        initialize()
        val metadata = IosTaskMetadata(
            downloadId = request.downloadId,
            ownerProfileKey = request.ownerProfileKey,
            destinationFileName = request.destinationFileName,
        )
        val storedResumeData = loadResumeData(request.downloadId)
        val storedCheckpoint = loadResumeCheckpoint(request.downloadId)
        val resumeData = storedResumeData?.takeIf {
            storedCheckpoint != null && resumeCheckpointMatches(
                expectedBytes = request.resumeDownloadedBytes,
                checkpointBytes = storedCheckpoint,
            )
        }
        if (storedResumeData != null && resumeData == null) {
            downloadsTransportLog.w {
                "event=resume_data_rejected download_id=${request.downloadId} catalog_bytes=${request.resumeDownloadedBytes} checkpoint_bytes=${storedCheckpoint ?: "missing"}"
            }
            removeResumeData(request.downloadId)
        }
        val task = resumeData?.let(session::downloadTaskWithResumeData)
            ?: session.downloadTaskWithRequest(request.toNativeRequest())
        if (resumeData != null) {
            removeResumeData(request.downloadId)
        }
        task.taskDescription = metadata.encode()

        synchronized(stateLock) {
            callbacksByDownloadId[request.downloadId] = callbacks
            tasksByDownloadId[request.downloadId]?.cancel()
            tasksByDownloadId[request.downloadId] = task
            pauseRequestedIds.remove(request.downloadId)
            terminalHandledIds.remove(request.downloadId)
        }

        val persistentSessionIdentifier = session.configuration.identifier
        callbacks.onTaskCreated(persistentSessionIdentifier, task.taskIdentifier.toLong())
        callbacks.onProgress(task.countOfBytesReceived.coerceAtLeast(0L), task.expectedByteCountOrNull())
        downloadsTransportLog.i {
            "event=task_created download_id=${request.downloadId} task_id=${task.taskIdentifier} session_id=${persistentSessionIdentifier ?: "foreground-test"} resume_data=${resumeData != null}"
        }
        task.resume()
        return IosDownloadsTaskHandle(this, request.downloadId)
    }

    fun pause(downloadId: String) {
        val task = synchronized(stateLock) {
            pauseRequestedIds += downloadId
            tasksByDownloadId[downloadId]
        } ?: return
        task.cancelByProducingResumeData { data ->
            val downloadedBytes = task.countOfBytesReceived.coerceAtLeast(0L)
            if (data != null) saveResumeData(downloadId, data, downloadedBytes)
            if (isCurrentTask(downloadId, task)) {
                dispatchProgress(
                    downloadId = downloadId,
                    downloadedBytes = downloadedBytes,
                    totalBytes = task.expectedByteCountOrNull(),
                )
            }
        }
        downloadsTransportLog.i {
            "event=task_pause_requested download_id=$downloadId task_id=${task.taskIdentifier}"
        }
    }

    fun cancel(downloadId: String) {
        val task = synchronized(stateLock) {
            callbacksByDownloadId.remove(downloadId)
            terminalHandledIds.remove(downloadId)
            pauseRequestedIds.remove(downloadId)
            tasksByDownloadId.remove(downloadId)
        }
        removeResumeData(downloadId)
        task?.cancel()
        downloadsTransportLog.i {
            "event=task_cancel_requested download_id=$downloadId task_id=${task?.taskIdentifier ?: "unknown"}"
        }
    }

    fun removeResumeData(downloadId: String) {
        removeStoredResumeData(downloadId)
    }

    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didWriteData: Long,
        totalBytesWritten: Long,
        totalBytesExpectedToWrite: Long,
    ) {
        val metadata = IosTaskMetadata.decode(downloadTask.taskDescription) ?: return
        if (!isCurrentTask(metadata.downloadId, downloadTask)) return
        val totalBytes = totalBytesExpectedToWrite.takeIf { it > 0L }
        dispatchProgress(metadata.downloadId, totalBytesWritten.coerceAtLeast(0L), totalBytes)
        downloadsTransportLog.d {
            "event=progress download_id=${metadata.downloadId} task_id=${downloadTask.taskIdentifier} downloaded_bytes=${totalBytesWritten.coerceAtLeast(0L)} total_bytes=${totalBytes ?: "unknown"}"
        }
    }

    override fun URLSession(
        session: NSURLSession,
        taskIsWaitingForConnectivity: NSURLSessionTask,
    ) {
        val metadata = IosTaskMetadata.decode(taskIsWaitingForConnectivity.taskDescription) ?: return
        if (!isCurrentTask(metadata.downloadId, taskIsWaitingForConnectivity)) return
        dispatchWaitingForConnectivity(metadata.downloadId)
        downloadsTransportLog.i {
            "event=task_waiting_for_connectivity download_id=${metadata.downloadId} task_id=${taskIsWaitingForConnectivity.taskIdentifier}"
        }
    }

    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didFinishDownloadingToURL: NSURL,
    ) {
        val metadata = IosTaskMetadata.decode(downloadTask.taskDescription)
        if (metadata == null) {
            downloadTask.cancel()
            return
        }
        if (!isCurrentTask(metadata.downloadId, downloadTask)) return
        val statusCode = (downloadTask.response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 200
        if (statusCode !in 200..299) {
            dispatchFailure(
                metadata.downloadId,
                runBlocking { getString(Res.string.network_request_failed_http, statusCode) },
            )
            markTerminalHandled(metadata.downloadId)
            return
        }

        dispatchFinalizing(metadata.downloadId)

        val sourcePath = didFinishDownloadingToURL.path
        val destinationPath = "${applicationSupportDownloadsRoot()}/${metadata.relativeMediaPath}"
        val parentPath = destinationPath.substringBeforeLast('/')
        createDirectory(parentPath)
        removePathIfExists(destinationPath)
        val moved = sourcePath != null && NSFileManager.defaultManager.moveItemAtPath(
            srcPath = sourcePath,
            toPath = destinationPath,
            error = null,
        )
        if (!moved) {
            dispatchFailure(
                metadata.downloadId,
                runBlocking { getString(Res.string.downloads_error_finalize_file_failed) },
            )
            markTerminalHandled(metadata.downloadId)
            return
        }

        excludeFromBackup(destinationPath)
        removeResumeData(metadata.downloadId)
        val finalSize = fileSizeOrNull(destinationPath)
        val expectedBytes = downloadTask.expectedByteCountOrNull() ?: finalSize
        markTerminalHandled(metadata.downloadId)
        dispatchSuccess(
            downloadId = metadata.downloadId,
            localFileUri = fileUriForPath(destinationPath),
            relativeMediaPath = metadata.relativeMediaPath,
            totalBytes = expectedBytes,
        )
        downloadsTransportLog.i {
            "event=file_finalized download_id=${metadata.downloadId} task_id=${downloadTask.taskIdentifier} downloaded_bytes=${finalSize ?: "unknown"} total_bytes=${expectedBytes ?: "unknown"}"
        }
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: NSError?,
    ) {
        val metadata = IosTaskMetadata.decode(task.taskDescription) ?: return
        val downloadId = metadata.downloadId
        if (!isCurrentTask(downloadId, task)) return
        val terminalHandled = synchronized(stateLock) {
            terminalHandledIds.remove(downloadId)
        }
        if (terminalHandled) {
            cleanupFinishedTask(downloadId)
            return
        }

        val wasPaused = synchronized(stateLock) { pauseRequestedIds.remove(downloadId) }
        val resumeData = didCompleteWithError
            ?.userInfo
            ?.get(NSURLSessionDownloadTaskResumeData) as? NSData
        if (resumeData != null) {
            saveResumeData(
                downloadId = downloadId,
                data = resumeData,
                downloadedBytes = task.countOfBytesReceived.coerceAtLeast(0L),
            )
        }

        if (wasPaused) {
            cleanupFinishedTask(downloadId)
            return
        }

        if (didCompleteWithError != null) {
            if (didCompleteWithError.code == CANNOT_RESUME_ERROR_CODE) {
                removeResumeData(downloadId)
            }
            downloadsTransportLog.w {
                "event=task_completed download_id=$downloadId task_id=${task.taskIdentifier} result=error error_domain=${didCompleteWithError.domain} error_code=${didCompleteWithError.code}"
            }
            dispatchFailure(downloadId, didCompleteWithError.localizedDescription)
        } else {
            downloadsTransportLog.w {
                "event=task_completed download_id=$downloadId task_id=${task.taskIdentifier} result=missing_file_callback"
            }
            dispatchFailure(downloadId, runBlocking { getString(Res.string.download_failed) })
        }
        cleanupFinishedTask(downloadId)
    }

    override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) {
        val identifier = session.configuration.identifier ?: return
        val handler = synchronized(stateLock) {
            backgroundCompletionHandlers.remove(identifier)
        } ?: return
        downloadsTransportLog.i {
            "event=background_events_finished session_id=$identifier"
        }
        NSOperationQueue.mainQueue.addOperationWithBlock(handler)
    }

    private fun reconcileSystemTasks() {
        session.getAllTasksWithCompletionHandler { tasks ->
            tasks.orEmpty().forEach { rawTask ->
                val task = rawTask as? NSURLSessionTask ?: return@forEach
                val metadata = IosTaskMetadata.decode(task.taskDescription)
                if (metadata == null || !DownloadsRepository.shouldRetainPlatformTask(metadata.downloadId)) {
                    task.cancel()
                    return@forEach
                }
                val downloadTask = task as? NSURLSessionDownloadTask ?: run {
                    task.cancel()
                    return@forEach
                }
                val shouldKeep = synchronized(stateLock) {
                    val current = tasksByDownloadId[metadata.downloadId]
                    when {
                        current == null || current.taskIdentifier == downloadTask.taskIdentifier -> {
                            tasksByDownloadId[metadata.downloadId] = downloadTask
                            true
                        }

                        current.taskIdentifier > downloadTask.taskIdentifier -> false
                        else -> {
                            current.cancel()
                            tasksByDownloadId[metadata.downloadId] = downloadTask
                            true
                        }
                    }
                }
                if (!shouldKeep) {
                    downloadTask.cancel()
                } else if (downloadTask.state == NSURLSessionTaskStateSuspended) {
                    downloadTask.resume()
                    downloadsTransportLog.i {
                        "event=system_task_resumed download_id=${metadata.downloadId} task_id=${downloadTask.taskIdentifier}"
                    }
                }
            }
            val trackedTasks = synchronized(stateLock) {
                tasksByDownloadId.values.toList()
            }
            val snapshots = trackedTasks.mapNotNull { task ->
                val metadata = IosTaskMetadata.decode(task.taskDescription) ?: return@mapNotNull null
                DownloadPlatformTaskSnapshot(
                    downloadId = metadata.downloadId,
                    sessionIdentifier = sessionIdentifier,
                    taskIdentifier = task.taskIdentifier.toLong(),
                    state = task.state.toDownloadPlatformTaskState(),
                    downloadedBytes = task.countOfBytesReceived.coerceAtLeast(0L),
                    totalBytes = task.expectedByteCountOrNull(),
                )
            }
            DownloadsRepository.onPlatformReconciliation(sessionIdentifier, snapshots)
            downloadsTransportLog.i {
                "event=system_tasks_enumerated session_id=$sessionIdentifier task_count=${snapshots.size}"
            }
        }
    }

    private fun dispatchProgress(downloadId: String, downloadedBytes: Long, totalBytes: Long?) {
        val callback = synchronized(stateLock) { callbacksByDownloadId[downloadId] }
        if (callback != null) {
            callback.onProgress(downloadedBytes, totalBytes)
        } else {
            DownloadsRepository.onPlatformProgress(downloadId, downloadedBytes, totalBytes)
        }
    }

    private fun dispatchWaitingForConnectivity(downloadId: String) {
        val callback = synchronized(stateLock) { callbacksByDownloadId[downloadId] }
        if (callback != null) {
            callback.onWaitingForConnectivity()
        } else {
            DownloadsRepository.onPlatformWaitingForConnectivity(downloadId)
        }
    }

    private fun dispatchFinalizing(downloadId: String) {
        val callback = synchronized(stateLock) { callbacksByDownloadId[downloadId] }
        if (callback != null) {
            callback.onFinalizing()
        } else {
            DownloadsRepository.onPlatformFinalizing(downloadId)
        }
    }

    private fun dispatchSuccess(
        downloadId: String,
        localFileUri: String,
        relativeMediaPath: String,
        totalBytes: Long?,
    ) {
        val callback = synchronized(stateLock) { callbacksByDownloadId[downloadId] }
        if (callback != null) {
            callback.onSuccess(localFileUri, relativeMediaPath, totalBytes)
        } else {
            DownloadsRepository.onPlatformSuccess(downloadId, localFileUri, relativeMediaPath, totalBytes)
        }
    }

    private fun dispatchFailure(downloadId: String, message: String) {
        val callback = synchronized(stateLock) { callbacksByDownloadId[downloadId] }
        if (callback != null) {
            callback.onFailure(message)
        } else {
            DownloadsRepository.onPlatformFailure(downloadId, message)
        }
    }

    private fun markTerminalHandled(downloadId: String) {
        synchronized(stateLock) {
            terminalHandledIds += downloadId
        }
    }

    private fun isCurrentTask(downloadId: String, task: NSURLSessionTask): Boolean =
        synchronized(stateLock) {
            tasksByDownloadId[downloadId]?.taskIdentifier == task.taskIdentifier
        }

    private fun cleanupFinishedTask(downloadId: String) {
        synchronized(stateLock) {
            callbacksByDownloadId.remove(downloadId)
            tasksByDownloadId.remove(downloadId)
            pauseRequestedIds.remove(downloadId)
            terminalHandledIds.remove(downloadId)
        }
    }
}

private class IosDownloadsTaskHandle(
    private val manager: IosBackgroundDownloadManager,
    private val downloadId: String,
) : DownloadsTaskHandle {
    override fun pause() {
        manager.pause(downloadId)
    }

    override fun cancel() {
        manager.cancel(downloadId)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun DownloadPlatformRequest.toNativeRequest(): NSMutableURLRequest {
    val url = NSURL(string = sourceUrl)
    return NSMutableURLRequest(
        uRL = url,
        cachePolicy = NSURLRequestReloadIgnoringLocalCacheData,
        timeoutInterval = DOWNLOAD_REQUEST_TIMEOUT_SECONDS,
    ).apply {
        setHTTPMethod("GET")
        setAllowsCellularAccess(networkPolicy.effectiveAllowsCellular)
        setAllowsExpensiveNetworkAccess(networkPolicy.allowExpensiveNetworks)
        setAllowsConstrainedNetworkAccess(networkPolicy.allowConstrainedNetworks)
        sourceHeaders.forEach { (key, value) ->
            setValue(value, forHTTPHeaderField = key)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun applicationSupportDownloadsRoot(): String {
    val path = "${NSHomeDirectory().trimEnd('/')}/Library/Application Support/Downloads"
    createDirectory(path)
    excludeFromBackup(path)
    return path
}

@OptIn(ExperimentalForeignApi::class)
private fun legacyDownloadsDirectoryPath(): String {
    val path = "${NSHomeDirectory().trimEnd('/')}/Documents/nuvio_downloads"
    createDirectory(path)
    return path
}

@OptIn(ExperimentalForeignApi::class)
private fun resumeDataPath(downloadId: String): String {
    val directory = "${applicationSupportDownloadsRoot()}/.resume"
    createDirectory(directory)
    return "$directory/${downloadId.safePathComponent()}.resume"
}

private fun resumeCheckpointKey(downloadId: String): String =
    "$RESUME_CHECKPOINT_DEFAULTS_PREFIX${downloadId.safePathComponent()}"

@OptIn(ExperimentalForeignApi::class)
private fun removeStoredResumeData(downloadId: String) {
    removePathIfExists(resumeDataPath(downloadId))
    NSUserDefaults.standardUserDefaults.removeObjectForKey(resumeCheckpointKey(downloadId))
}

@OptIn(ExperimentalForeignApi::class)
private fun loadResumeData(downloadId: String): NSData? =
    NSData.create(contentsOfFile = resumeDataPath(downloadId))

@OptIn(ExperimentalForeignApi::class)
private fun loadResumeCheckpoint(downloadId: String): Long? {
    val defaults = NSUserDefaults.standardUserDefaults
    val key = resumeCheckpointKey(downloadId)
    if (defaults.objectForKey(key) == null) return null
    return defaults.integerForKey(key)
}

private fun resumeCheckpointMatches(
    expectedBytes: Long,
    checkpointBytes: Long,
): Boolean {
    val normalizedExpected = expectedBytes.coerceAtLeast(0L)
    val normalizedCheckpoint = checkpointBytes.coerceAtLeast(0L)
    val difference = if (normalizedExpected >= normalizedCheckpoint) {
        normalizedExpected - normalizedCheckpoint
    } else {
        normalizedCheckpoint - normalizedExpected
    }
    return difference <= RESUME_CHECKPOINT_TOLERANCE_BYTES
}

@OptIn(ExperimentalForeignApi::class)
private fun saveResumeData(downloadId: String, data: NSData, downloadedBytes: Long): Boolean {
    val path = resumeDataPath(downloadId)
    removeStoredResumeData(downloadId)
    val saved = NSFileManager.defaultManager.createFileAtPath(
        path = path,
        contents = data,
        attributes = null,
    )
    if (saved) {
        NSUserDefaults.standardUserDefaults.setInteger(
            downloadedBytes.coerceAtLeast(0L),
            forKey = resumeCheckpointKey(downloadId),
        )
    }
    return saved
}

@OptIn(ExperimentalForeignApi::class)
private fun createDirectory(path: String) {
    NSFileManager.defaultManager.createDirectoryAtPath(
        path = path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun excludeFromBackup(path: String) {
    runCatching {
        NSURL.fileURLWithPath(path).setResourceValue(
            value = true,
            forKey = NSURLIsExcludedFromBackupKey,
            error = null,
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun removePathIfExists(path: String): Boolean {
    if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return true
    return NSFileManager.defaultManager.removeItemAtPath(path, null)
}

@OptIn(ExperimentalForeignApi::class)
private fun fileSizeOrNull(path: String): Long? {
    val value = NSFileManager.defaultManager
        .attributesOfItemAtPath(path, error = null)
        ?.get("NSFileSize")
    return when (value) {
        is Long -> value
        is Number -> value.toLong()
        else -> null
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSURLSessionTask.expectedByteCountOrNull(): Long? =
    countOfBytesExpectedToReceive.takeIf { it > 0L }

private fun String.toLocalPath(): String? {
    val value = trim()
    if (value.startsWith("file:")) {
        return NSURL(string = value).path ?: value.removePrefix("file://")
    }
    return value.takeIf { it.isNotBlank() }
}

private fun String.normalizedRelativeMediaPath(): String? {
    val normalized = trim().trimStart('/')
    if (normalized.isBlank()) return null
    val components = normalized.split('/')
    if (components.any { it.isBlank() || it == "." || it == ".." }) return null
    val lastIndex = components.lastIndex
    val isCanonical = components.withIndex().all { (index, component) ->
        val safeComponent = if (index == lastIndex) {
            component.safeFileName()
        } else {
            component.safePathComponent()
        }
        component == safeComponent
    }
    return components.joinToString("/").takeIf { isCanonical }
}

private fun String.safePathComponent(): String =
    trim().replace(Regex("[^A-Za-z0-9._:-]"), "_").take(120)

private fun String.safeFileName(): String =
    trim().substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ -]"), "_").take(160)

@OptIn(ExperimentalForeignApi::class)
private fun fileUriForPath(path: String): String =
    NSURL.fileURLWithPath(path).absoluteString ?: "file://$path"

@OptIn(ExperimentalForeignApi::class)
private fun platform.Foundation.NSURLSessionTaskState.toDownloadPlatformTaskState(): DownloadPlatformTaskState =
    when (this) {
        NSURLSessionTaskStateRunning -> DownloadPlatformTaskState.Running
        NSURLSessionTaskStateSuspended -> DownloadPlatformTaskState.Suspended
        NSURLSessionTaskStateCanceling -> DownloadPlatformTaskState.Canceling
        NSURLSessionTaskStateCompleted -> DownloadPlatformTaskState.Completed
        else -> DownloadPlatformTaskState.Completed
    }
