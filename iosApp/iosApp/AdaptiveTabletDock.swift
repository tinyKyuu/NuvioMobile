import SwiftUI
import UIKit

@available(iOS 16.0, *)
struct AdaptiveTabletRoot<Content: View>: View {
    @ObservedObject var appCoordinator: AppNavigationCoordinator
    @ObservedObject var selectedCoordinator: TabNavigationCoordinator
    @ObservedObject var iconStore: NativeTabIconStore
    let selection: Binding<NuvioAppTab>
    @ViewBuilder let content: Content
    @ScaledMetric(relativeTo: .caption) private var itemHeight = 56.0
    @ScaledMetric(relativeTo: .caption) private var sideItemWidth = 76.0

    var body: some View {
        GeometryReader { geometry in
            let layout = AdaptiveDockLayout(
                window: geometry.size,
                itemHeight: itemHeight,
                sideItemWidth: sideItemWidth
            )
            let atRoot = appCoordinator.isMainContentVisible && selectedCoordinator.path.isEmpty
            let visible = atRoot && !appCoordinator.isRootTabBarSuppressed

            // Keep this content and dock at the same structural identity when
            // the window changes shape. Padding reserves real content space.
            content
                .padding(.trailing, atRoot ? layout.reservedWidth : 0)
                .padding(.bottom, atRoot && !appCoordinator.isRootTabContentSuppressed ? layout.reservedHeight : 0)
                .overlay(alignment: layout.isVertical ? .trailing : .bottom) {
                    AdaptiveTabletDock(
                        layout: layout,
                        selection: selection,
                        appCoordinator: appCoordinator,
                        iconStore: iconStore
                    )
                    .padding(layout.isVertical ? .trailing : .bottom, layout.isVertical ? 12 : 8)
                    .opacity(visible ? 1 : 0)
                    .allowsHitTesting(visible)
                    .accessibilityHidden(!visible)
                }
        }
        // Compose still handles its own IME insets. The dock's parent must not
        // move above the keyboard or switch axes when the keyboard opens.
        .ignoresSafeArea(.keyboard)
        .background(Color(uiColor: nuvioBackgroundColor).ignoresSafeArea())
    }
}

@available(iOS 16.0, *)
private struct AdaptiveTabletDock: View {
    let layout: AdaptiveDockLayout
    @Binding var selection: NuvioAppTab
    @ObservedObject var appCoordinator: AppNavigationCoordinator
    @ObservedObject var iconStore: NativeTabIconStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.layoutDirection) private var layoutDirection
    @Namespace private var glassNamespace

    private var spring: Animation? {
        reduceMotion ? nil : .interpolatingSpring(stiffness: 320, damping: 29)
    }

    private var center: CGPoint {
        layout.selectionCenter(
            index: NuvioAppTab.allCases.firstIndex(of: selection) ?? 0,
            rightToLeft: layoutDirection == .rightToLeft
        )
    }

    var body: some View {
        ZStack(alignment: .topLeading) {
            glassLayers
                .allowsHitTesting(false)
            let stack = layout.isVertical
                ? AnyLayout(VStackLayout(spacing: 0))
                : AnyLayout(HStackLayout(spacing: 0))
            stack {
                ForEach(NuvioAppTab.allCases, id: \.self) { tab in
                    tabButton(tab)
                }
            }
            .padding(AdaptiveDockLayout.inset)
        }
        .frame(width: layout.size.width, height: layout.size.height)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("adaptive-root-dock")
    }

    @ViewBuilder
    private var glassLayers: some View {
        if #available(iOS 26.0, *), !reduceTransparency {
            GlassEffectContainer(spacing: 0) {
                ZStack(alignment: .topLeading) {
                    Capsule()
                        .fill(.clear)
                        .frame(width: layout.size.width, height: layout.size.height)
                        .glassEffect(.regular, in: .capsule)
                    Capsule()
                        .fill(.clear)
                        .frame(width: layout.itemSize.width, height: layout.itemSize.height)
                        .glassEffect(.regular.tint(Color(uiColor: iconStore.accentColor).opacity(0.16)).interactive(), in: .capsule)
                        .glassEffectID("root-selection", in: glassNamespace)
                        .position(center)
                        .animation(spring, value: selection)
                }
            }
        } else {
            Capsule()
                .fill(reduceTransparency ? AnyShapeStyle(Color(uiColor: .secondarySystemBackground)) : AnyShapeStyle(.regularMaterial))
                .frame(width: layout.size.width, height: layout.size.height)
            Capsule()
                .fill(Color.primary.opacity(reduceTransparency ? 0.18 : 0.12))
                .frame(width: layout.itemSize.width, height: layout.itemSize.height)
                .position(center)
                .animation(spring, value: selection)
        }
    }

    private func tabButton(_ tab: NuvioAppTab) -> some View {
        Button {
            selection = tab
        } label: {
            VStack(spacing: 3) {
                Image(uiImage: iconStore.image(for: tab, selected: selection == tab))
                    .resizable()
                    .scaledToFit()
                    .frame(width: 26, height: 26)
                Text(appCoordinator.title(for: tab))
                    .font(.caption2.weight(.semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
            }
            .foregroundStyle(selection == tab ? Color(uiColor: iconStore.accentColor) : Color.secondary)
            .frame(width: layout.itemSize.width, height: layout.itemSize.height)
            .contentShape(Capsule())
        }
        .buttonStyle(DockPressStyle(reduceMotion: reduceMotion))
        .disabled(!appCoordinator.isAppReady && tab != .home)
        .accessibilityLabel(appCoordinator.title(for: tab))
        .accessibilityAddTraits(selection == tab ? .isSelected : [])
        .accessibilityIdentifier("root-dock-\(tab.rawValue.lowercased())")
        .highPriorityGesture(LongPressGesture(minimumDuration: 0.5).onEnded { _ in
            guard tab == .settings, appCoordinator.isAppReady else { return }
            if #available(iOS 26.0, *) {
                appCoordinator.isProfileSwitcherPresented = true
            } else {
                appCoordinator.appGateController.requestProfileSelection()
            }
        })
        .accessibilityActions {
            if tab == .settings {
                Button(appCoordinator.localizedSwitchProfileTitle) {
                    if #available(iOS 26.0, *) {
                        appCoordinator.isProfileSwitcherPresented = true
                    } else {
                        appCoordinator.appGateController.requestProfileSelection()
                    }
                }
            }
        }
        .popover(isPresented: Binding(
            get: { tab == .settings && appCoordinator.isProfileSwitcherPresented },
            set: { appCoordinator.isProfileSwitcherPresented = $0 }
        )) {
            if #available(iOS 26.0, *) {
                NativeProfileSwitcherView(
                    controller: appCoordinator.profileSwitcherController,
                    title: appCoordinator.localizedSwitchProfileTitle,
                    addProfileTitle: appCoordinator.localizedAddProfileTitle,
                    onManageProfiles: appCoordinator.openProfileManagement
                )
            }
        }
    }
}

private struct DockPressStyle: ButtonStyle {
    let reduceMotion: Bool
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.92 : 1)
            .animation(reduceMotion ? nil : .spring(response: 0.25, dampingFraction: 0.65), value: configuration.isPressed)
    }
}

/// One observer tied to this scene's window, including floating iPad keyboards.
struct RootKeyboardObserver: UIViewRepresentable {
    let onVisibilityChanged: (Bool) -> Void

    func makeUIView(context: Context) -> KeyboardProbeView {
        let view = KeyboardProbeView()
        view.onVisibilityChanged = onVisibilityChanged
        return view
    }

    func updateUIView(_ view: KeyboardProbeView, context: Context) {
        view.onVisibilityChanged = onVisibilityChanged
    }
}

final class KeyboardProbeView: UIView {
    var onVisibilityChanged: ((Bool) -> Void)?
    private var observers: [NSObjectProtocol] = []
    private var visible = false

    init() {
        super.init(frame: .zero)
        isUserInteractionEnabled = false
        keyboardLayoutGuide.followsUndockedKeyboard = true
        for name in [UIResponder.keyboardWillShowNotification, UIResponder.keyboardWillChangeFrameNotification] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] notification in
                guard let self, let window = self.window, window.isKeyWindow,
                      let frame = notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else { return }
                let local = window.convert(frame, from: window.screen.coordinateSpace)
                // Ignore a hardware keyboard's shortcut strip alone. A floating
                // software keyboard still has a full keyboard-sized frame.
                if local.height > 100 && window.bounds.intersects(local) {
                    self.publish(true)
                }
            })
        }
        observers.append(NotificationCenter.default.addObserver(forName: UIResponder.keyboardDidHideNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self, self.window?.isKeyWindow == true else { return }
            self.publish(false)
        })
        observers.append(NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            DispatchQueue.main.async { self?.reconcileLayout() }
        })
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    deinit { observers.forEach(NotificationCenter.default.removeObserver) }

    private func reconcileLayout() {
        guard window?.isKeyWindow == true else { return }
        publish(keyboardLayoutGuide.layoutFrame.height > 100)
    }

    private func publish(_ next: Bool) {
        guard next != visible else { return }
        visible = next
        onVisibilityChanged?(next)
    }
}
