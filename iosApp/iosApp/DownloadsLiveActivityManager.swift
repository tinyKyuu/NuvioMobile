import Foundation
import UIKit
import UserNotifications
#if canImport(ActivityKit) && os(iOS) && !targetEnvironment(macCatalyst)
import ActivityKit
#endif

private let downloadsLiveStatusUpdatedNotification = Notification.Name("NuvioDownloadsLiveStatusUpdated")
private let downloadTerminalStatusUpdatedNotification = Notification.Name("NuvioDownloadTerminalStatusUpdated")
private let downloadsLiveStatusPayloadKey = "nuvio.downloads.live_status.payload"
private let downloadTerminalStatusPayloadKey = "nuvio.downloads.terminal_status.payload"

final class DownloadsLiveActivityManager {
    static let shared = DownloadsLiveActivityManager()

    private var observers: [NSObjectProtocol] = []
    private var pendingSyncRequest: DownloadsLiveActivitySyncRequest?
    private var syncTask: Task<Void, Never>?

    private init() {}

    func start() {
        guard observers.isEmpty else { return }

        let center = NotificationCenter.default
        observers.append(
            center.addObserver(
                forName: downloadsLiveStatusUpdatedNotification,
                object: nil,
                // Kotlin can post while holding its status lock. Observe on the
                // posting thread so a progress callback never waits for main,
                // which may be waiting for the same Kotlin lock.
                queue: nil
            ) { [weak self] _ in
                self?.syncFromPayloadStore()
            }
        )
        observers.append(
            center.addObserver(
                forName: downloadTerminalStatusUpdatedNotification,
                object: nil,
                queue: nil
            ) { [weak self] _ in
                self?.postTerminalNotification()
            }
        )
        observers.append(
            center.addObserver(
                forName: UIApplication.didEnterBackgroundNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in
                self?.syncFromPayloadStore()
            }
        )
        observers.append(
            center.addObserver(
                forName: UIApplication.didBecomeActiveNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in
                self?.syncFromPayloadStore()
            }
        )

        syncFromPayloadStore()
    }

    private func syncFromPayloadStore() {
#if canImport(ActivityKit) && os(iOS) && !targetEnvironment(macCatalyst)
        guard #available(iOS 16.1, *) else { return }
        guard Thread.isMainThread else {
            DispatchQueue.main.async { [weak self] in
                self?.syncFromPayloadStore()
            }
            return
        }

        pendingSyncRequest = DownloadsLiveActivitySyncRequest(
            payload: loadPayload(),
            appIsActive: UIApplication.shared.applicationState == .active
        )
        guard syncTask == nil else { return }
        syncTask = Task { @MainActor [weak self] in
            await self?.drainSyncRequests()
        }
#endif
    }

    private func loadPayload() -> DownloadsLiveStatusPayload? {
        guard let encoded = UserDefaults.standard.string(forKey: downloadsLiveStatusPayloadKey) else {
            return nil
        }
        let data = Data(encoded.utf8)
        return try? JSONDecoder().decode(DownloadsLiveStatusPayload.self, from: data)
    }

#if canImport(ActivityKit) && os(iOS) && !targetEnvironment(macCatalyst)
    @MainActor
    @available(iOS 16.1, *)
    private func drainSyncRequests() async {
        while let request = pendingSyncRequest {
            pendingSyncRequest = nil
            await apply(request.payload, appIsActive: request.appIsActive)
        }
        syncTask = nil
    }

    @available(iOS 16.1, *)
    private func apply(_ payload: DownloadsLiveStatusPayload?, appIsActive: Bool) async {
        let activities = Activity<DownloadsLiveActivityAttributes>.activities

        guard let payload else {
            for activity in activities {
                await activity.end(dismissalPolicy: .immediate)
            }
            return
        }

        let existing = activities.first { activity in
            activity.attributes.downloadId == payload.id
        }
        for activity in activities where activity.id != existing?.id {
            await activity.end(dismissalPolicy: .immediate)
        }

        let state: DownloadsLiveActivityAttributes.ContentState
        if !appIsActive && payload.status.lowercased() == "downloading" {
            state = DownloadsLiveActivityAttributes.ContentState(
                status: "Background",
                progressPercent: -1,
                transferredText: "Open Nuvio for current progress",
                queuedCount: payload.queuedCount
            )
        } else {
            state = DownloadsLiveActivityAttributes.ContentState(
                status: payload.status,
                progressPercent: payload.progressPercent,
                transferredText: transferredText(payload),
                queuedCount: payload.queuedCount
            )
        }

        if let existing, existing.attributes.downloadId == payload.id {
            await existing.update(using: state)
            return
        }

        if let existing {
            await existing.end(dismissalPolicy: .immediate)
        }

        let attributes = DownloadsLiveActivityAttributes(
            downloadId: payload.id,
            title: payload.title,
            subtitle: payload.subtitle
        )

        _ = try? Activity<DownloadsLiveActivityAttributes>.request(
            attributes: attributes,
            contentState: state,
            pushType: nil
        )
    }
#endif

    private func postTerminalNotification() {
        guard
            let encoded = UserDefaults.standard.string(forKey: downloadTerminalStatusPayloadKey),
            let payload = try? JSONDecoder().decode(
                DownloadsTerminalStatusPayload.self,
                from: Data(encoded.utf8)
            )
        else { return }

        let center = UNUserNotificationCenter.current()
        center.getNotificationSettings { settings in
            let allowed = settings.authorizationStatus == .authorized ||
                settings.authorizationStatus == .provisional ||
                settings.authorizationStatus == .ephemeral
            guard allowed else { return }

            let content = UNMutableNotificationContent()
            content.title = payload.status.lowercased() == "completed"
                ? "Download completed"
                : "Download failed"
            if let message = payload.message, !message.isEmpty {
                content.body = message
            } else {
                content.body = [payload.title, payload.subtitle]
                    .filter { !$0.isEmpty }
                    .joined(separator: " • ")
            }
            content.sound = .default
            content.userInfo = ["deeplink": "nuvio://downloads"]
            center.add(
                UNNotificationRequest(
                    identifier: "nuvio.download.terminal.\(payload.id).\(payload.status)",
                    content: content,
                    trigger: nil
                )
            )
        }
    }

    private func transferredText(_ payload: DownloadsLiveStatusPayload) -> String {
        let downloaded = formatBytes(payload.downloadedBytes)
        if let total = payload.totalBytes {
            return "\(downloaded) / \(formatBytes(total))"
        }
        return downloaded
    }

    private func formatBytes(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useKB, .useMB, .useGB]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }
}

#if canImport(ActivityKit) && os(iOS) && !targetEnvironment(macCatalyst)
@available(iOS 16.1, *)
struct DownloadsLiveActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        let status: String
        let progressPercent: Int
        let transferredText: String
        let queuedCount: Int
    }

    let downloadId: String
    let title: String
    let subtitle: String
}
#endif

private struct DownloadsLiveStatusPayload: Decodable {
    let id: String
    let title: String
    let subtitle: String
    let status: String
    let downloadedBytes: Int64
    let totalBytes: Int64?
    let queuedCount: Int
    let progressPercent: Int
}

private struct DownloadsLiveActivitySyncRequest {
    let payload: DownloadsLiveStatusPayload?
    let appIsActive: Bool
}

private struct DownloadsTerminalStatusPayload: Decodable {
    let id: String
    let title: String
    let subtitle: String
    let status: String
    let message: String?
}
