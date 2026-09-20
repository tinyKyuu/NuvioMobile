import Foundation
import CoreGraphics

/// Uses the full app window, never the keyboard-reduced content rectangle.
struct AdaptiveDockLayout: Equatable {
    static let inset: CGFloat = 4
    let isVertical: Bool
    let itemSize: CGSize

    init(window: CGSize, itemHeight: CGFloat = 56, sideItemWidth: CGFloat = 76) {
        let verticalItemHeight = max(itemHeight, sideItemWidth)
        isVertical = window.width >= 900 && window.width > window.height &&
            window.height >= verticalItemHeight * 4 + 40
        itemSize = CGSize(
            width: isVertical ? sideItemWidth : max(44, (min(560, window.width - 32) - 8) / 4),
            // A side endpoint must be at least as tall as it is wide so its
            // capsule radius remains concentric with the outside capsule.
            height: isVertical ? verticalItemHeight : itemHeight
        )
    }

    var size: CGSize {
        CGSize(
            width: itemSize.width * (isVertical ? 1 : 4) + Self.inset * 2,
            height: itemSize.height * (isVertical ? 4 : 1) + Self.inset * 2
        )
    }

    var reservedWidth: CGFloat { isVertical ? size.width + 24 : 0 }
    var reservedHeight: CGFloat { isVertical ? 0 : size.height + 16 }

    func selectionCenter(index: Int, rightToLeft: Bool) -> CGPoint {
        let index = min(3, max(0, index))
        let visualIndex = !isVertical && rightToLeft ? 3 - index : index
        return CGPoint(
            x: Self.inset + itemSize.width * (isVertical ? 0.5 : CGFloat(visualIndex) + 0.5),
            y: Self.inset + itemSize.height * (isVertical ? CGFloat(index) + 0.5 : 0.5)
        )
    }
}

/// An inactive controller may update its own state, but cannot clear another
/// tab's Downloads-management suppression or the window's keyboard state.
struct RootDockVisibility<Tab: Hashable> {
    private var suppressedTabs: Set<Tab> = []
    var keyboardVisible = false

    mutating func setSuppressed(_ suppressed: Bool, for tab: Tab) {
        if suppressed { suppressedTabs.insert(tab) }
        else { suppressedTabs.remove(tab) }
    }

    func isSuppressed(for tab: Tab) -> Bool {
        keyboardVisible || isTabSuppressed(tab)
    }

    func isTabSuppressed(_ tab: Tab) -> Bool {
        suppressedTabs.contains(tab)
    }
}
