import Foundation
import CoreGraphics

var checks = 0
func check(_ condition: @autoclosure () -> Bool, _ message: String) {
    precondition(condition(), message)
    checks += 1
}

let portrait = AdaptiveDockLayout(window: CGSize(width: 1024, height: 1366))
let landscape = AdaptiveDockLayout(window: CGSize(width: 1366, height: 1024))
let split = AdaptiveDockLayout(window: CGSize(width: 700, height: 600))
check(!portrait.isVertical, "Portrait must use the bottom dock")
check(landscape.isVertical, "A wide landscape window must reserve a side strip")
check(!split.isVertical, "A narrow window must keep phone-like navigation")
check(portrait.reservedWidth == 0 && portrait.reservedHeight > portrait.size.height, "Bottom clearance")
check(landscape.reservedHeight == 0 && landscape.reservedWidth > landscape.size.width, "Side clearance")

for layout in [portrait, landscape, split] {
    let first = layout.selectionCenter(index: 0, rightToLeft: false)
    let last = layout.selectionCenter(index: 3, rightToLeft: false)
    check(first.x - layout.itemSize.width / 2 == 4, "Equal leading inset")
    check(first.y - layout.itemSize.height / 2 == 4, "Equal top inset")
    check(layout.size.width - last.x - layout.itemSize.width / 2 == 4, "Equal trailing inset")
    check(layout.size.height - last.y - layout.itemSize.height / 2 == 4, "Equal bottom inset")
    let outerRadius = min(layout.size.width, layout.size.height) / 2
    let innerRadius = min(layout.itemSize.width, layout.itemSize.height) / 2
    check(outerRadius - innerRadius == 4, "Endpoint radii must be concentric")
    check(layout.selectionCenter(index: 1, rightToLeft: false) != first, "Selection changes in one coordinate space")
}
check(portrait.selectionCenter(index: 0, rightToLeft: true) == portrait.selectionCenter(index: 3, rightToLeft: false), "RTL reverses bottom positions")
check(landscape.selectionCenter(index: 0, rightToLeft: true) == landscape.selectionCenter(index: 0, rightToLeft: false), "RTL preserves vertical order")

var visibility = RootDockVisibility<String>()
visibility.setSuppressed(true, for: "Library")
visibility.setSuppressed(false, for: "Home")
check(visibility.isSuppressed(for: "Library"), "Inactive tab cannot clear Downloads suppression")
check(!visibility.isSuppressed(for: "Home"), "Downloads state does not suppress another tab")
visibility.keyboardVisible = true
visibility.setSuppressed(false, for: "Library")
check(!visibility.isTabSuppressed("Library"), "Keyboard must not release the bottom dock's content reservation")
check(visibility.isSuppressed(for: "Library"), "Controller disposal cannot clear keyboard state")
check(visibility.isSuppressed(for: "Home"), "Keyboard hides either orientation and every tab")
visibility.keyboardVisible = false
check(!visibility.isSuppressed(for: "Library"), "Dismissal reveals the dock without changing its layout")
visibility.setSuppressed(true, for: "Library")
visibility.keyboardVisible = true
visibility.keyboardVisible = false
check(visibility.isSuppressed(for: "Library"), "Keyboard dismissal cannot expose dock during Downloads management")
print("Passed \(checks) adaptive dock layout and visibility checks")
