package com.nuvio.app.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Scroll-aware state for the floating navigation bar.
 * Tracks scroll direction and exposes a label visibility fraction (1 = fully visible, 0 = hidden).
 */
@Stable
class NuvioNavBarScrollState {
    /** 1f = labels fully visible (expanded), 0f = labels hidden (collapsed, icons only) */
    var labelVisibility by mutableFloatStateOf(1f)
        private set

    private var accumulatedDelta = 0f

    /** Call to expand (show labels) – e.g. when user scrolls back to top */
    fun expand() {
        labelVisibility = 1f
        accumulatedDelta = 0f
    }

    /** Call to collapse (hide labels) */
    fun collapse() {
        labelVisibility = 0f
        accumulatedDelta = 0f
    }

    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val deltaY = available.y
            if (deltaY == 0f) return Offset.Zero

            accumulatedDelta += deltaY

            if (accumulatedDelta < -SCROLL_THRESHOLD && labelVisibility != 0f) {
                // Scrolling down past threshold → snap collapse
                labelVisibility = 0f
                accumulatedDelta = 0f
            } else if (accumulatedDelta > SCROLL_THRESHOLD && labelVisibility != 1f) {
                // Scrolling up past threshold → snap expand
                labelVisibility = 1f
                accumulatedDelta = 0f
            }

            // Reset accumulator if direction changed
            if (deltaY < 0f && accumulatedDelta > 0f) accumulatedDelta = deltaY
            if (deltaY > 0f && accumulatedDelta < 0f) accumulatedDelta = deltaY

            return Offset.Zero // Don't consume any scroll
        }
    }

    companion object {
        private const val SCROLL_THRESHOLD = 60f
    }
}

@Composable
fun rememberNuvioNavBarScrollState(): NuvioNavBarScrollState {
    return androidx.compose.runtime.remember { NuvioNavBarScrollState() }
}

/**
 * Floating pill-shaped navigation bar with scroll-responsive labels.
 *
 * @param hazeState Optional [HazeState] whose source is placed on the content behind this bar.
 *                  When provided, the pill gets a blur-through effect.
 */
@Composable
fun NuvioNavigationBar(
    modifier: Modifier = Modifier,
    scrollState: NuvioNavBarScrollState? = null,
    hazeState: HazeState? = null,
    maxWidth: Dp? = null,
    horizontalPadding: Dp? = null,
    visualStyle: NuvioNavigationBarVisualStyle = NuvioNavigationBarVisualStyle.Standard,
    selectedIndex: Int? = null,
    itemCount: Int = 0,
    content: @Composable NuvioNavigationBarScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val labelFraction by animateFloatAsState(
        targetValue = scrollState?.labelVisibility ?: 1f,
        animationSpec = tween(
            durationMillis = NuvioTokens.Motion.sheetEnterMillis,
            easing = NuvioTokens.Motion.standard,
        ),
        label = "nav_label_alpha",
    )

    val navigationBarInsets = nuvioBottomNavigationBarInsets()
    val bottomSafePadding = navigationBarBottomPadding(
        visualStyle = visualStyle,
        windowInsetsBottom = navigationBarInsets.asPaddingValues().calculateBottomPadding(),
        physicalBottom = platformPhysicalBottomInset(),
    )

    // Dynamic horizontal padding: pill shrinks when labels are hidden — driven by same labelFraction
    val expandedHorizontalPadding = 28.dp
    val collapsedHorizontalPadding = 58.dp
    val resolvedHorizontalPadding = horizontalPadding
        ?: expandedHorizontalPadding +
        (collapsedHorizontalPadding - expandedHorizontalPadding) * (1f - labelFraction)
    val shape = RoundedCornerShape(NuvioTokens.Radius.full)
    val containerColor = when (visualStyle) {
        NuvioNavigationBarVisualStyle.Standard ->
            Color(0xFF1C1C1E).copy(alpha = if (hazeState != null) 0.55f else 0.82f)
        NuvioNavigationBarVisualStyle.IosTablet ->
            tokens.colors.surface.copy(
                alpha = if (hazeState != null) tokens.opacity.overlayMedium else tokens.opacity.overlayHeavy,
            )
    }

    // Outer container — no background, just safe padding
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = bottomSafePadding + nuvioBottomNavigationExtraVerticalPadding + NuvioTokens.Space.s8),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // The floating pill
        val pillModifier = Modifier
            .padding(horizontal = resolvedHorizontalPadding)
            .then(if (maxWidth != null) Modifier.widthIn(max = maxWidth) else Modifier)
            .fillMaxWidth()
            .then(
                if (visualStyle == NuvioNavigationBarVisualStyle.IosTablet) {
                    Modifier.shadow(tokens.elevation.overlay, shape, clip = false)
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .then(
                if (hazeState != null) {
                    Modifier.hazeEffect(state = hazeState) {
                        blurRadius = 24.dp
                    }
                } else {
                    Modifier
                },
            )
            .background(containerColor)
            .then(
                if (visualStyle == NuvioNavigationBarVisualStyle.IosTablet) {
                    Modifier.border(tokens.borders.hairline, tokens.colors.borderStrong, shape)
                } else {
                    Modifier
                },
            )

        Box(modifier = pillModifier) {
            if (
                visualStyle == NuvioNavigationBarVisualStyle.IosTablet &&
                selectedIndex != null &&
                itemCount > 0
            ) {
                IosTabletSelectionIndicator(
                    modifier = Modifier.matchParentSize(),
                    selectedIndex = selectedIndex,
                    itemCount = itemCount,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = NuvioTokens.Space.s6,
                        vertical = NuvioTokens.Space.s4,
                    ),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NuvioNavigationBarScopeImpl(
                    rowScope = this,
                    labelFraction = labelFraction,
                    usesSharedSelectionIndicator = visualStyle == NuvioNavigationBarVisualStyle.IosTablet,
                ).content()
            }
        }
    }
}

enum class NuvioNavigationBarVisualStyle {
    Standard,
    IosTablet,
}

internal fun navigationBarBottomPadding(
    visualStyle: NuvioNavigationBarVisualStyle,
    windowInsetsBottom: Dp,
    physicalBottom: Dp,
): Dp = if (visualStyle == NuvioNavigationBarVisualStyle.IosTablet) {
    physicalBottom
} else {
    windowInsetsBottom
}

internal fun sharedSelectionIndicatorOffset(
    slotWidth: Dp,
    selectedIndex: Int,
    itemCount: Int,
): Dp = slotWidth * selectedIndex.coerceIn(0, (itemCount - 1).coerceAtLeast(0))

@Composable
private fun IosTabletSelectionIndicator(
    modifier: Modifier,
    selectedIndex: Int,
    itemCount: Int,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(NuvioTokens.Radius.full)
    BoxWithConstraints(
        modifier = modifier
            .padding(
                horizontal = NuvioTokens.Space.s6,
                vertical = NuvioTokens.Space.s4,
            ),
    ) {
        val slotWidth = maxWidth / itemCount
        val targetOffset = sharedSelectionIndicatorOffset(
            slotWidth = slotWidth,
            selectedIndex = selectedIndex,
            itemCount = itemCount,
        )
        val animatedOffset by animateDpAsState(
            targetValue = targetOffset,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "ios_tablet_nav_indicator_offset",
        )
        Box(
            modifier = Modifier
                .offset(x = animatedOffset)
                .width(slotWidth)
                .fillMaxHeight()
                .padding(horizontal = NuvioTokens.Space.s2)
                .shadow(tokens.elevation.raised, shape, clip = false)
                .clip(shape)
                .background(tokens.colors.overlaySelected)
                .border(
                    tokens.borders.hairline,
                    tokens.colors.textPrimary.copy(alpha = tokens.opacity.pressed),
                    shape,
                ),
        )
    }
}

interface NuvioNavigationBarScope {
    @Composable
    fun NavItem(
        selected: Boolean,
        onClick: () -> Unit,
        icon: ImageVector,
        contentDescription: String?,
        modifier: Modifier = Modifier,
        label: String? = null,
        reserveLabelSpace: Boolean = label != null,
    )

    @Composable
    fun NavItem(
        selected: Boolean,
        onClick: () -> Unit,
        icon: DrawableResource,
        contentDescription: String?,
        modifier: Modifier = Modifier,
        label: String? = null,
        reserveLabelSpace: Boolean = label != null,
    )

    @Composable
    fun NavItem(
        selected: Boolean,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        label: String? = null,
        reserveLabelSpace: Boolean = label != null,
        content: @Composable () -> Unit,
    )
}

private class NuvioNavigationBarScopeImpl(
    private val rowScope: androidx.compose.foundation.layout.RowScope,
    private val labelFraction: Float,
    private val usesSharedSelectionIndicator: Boolean,
) : NuvioNavigationBarScope {

    @Composable
    override fun NavItem(
        selected: Boolean,
        onClick: () -> Unit,
        icon: ImageVector,
        contentDescription: String?,
        modifier: Modifier,
        label: String?,
        reserveLabelSpace: Boolean,
    ) {
        val tokens = MaterialTheme.nuvio
        val palette = ThemeColors.getColorPalette(MaterialTheme.appTheme)
        val iconColor by animateColorAsState(
            targetValue = if (selected) tokens.colors.accent else tokens.colors.textMuted,
            label = "nav_icon_color",
        )
        // Selected item gets a pill-shaped highlight using accent at low opacity
        val selectedBgColor by animateColorAsState(
            targetValue = if (selected && !usesSharedSelectionIndicator) {
                tokens.colors.accent.copy(alpha = NuvioTokens.Opacity.selected)
            } else {
                Color.Transparent
            },
            label = "nav_bg_color",
        )
        val selectedScale by animateFloatAsState(
            targetValue = if (selected && usesSharedSelectionIndicator) 1.08f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
            label = "nav_selection_scale",
        )

        with(rowScope) {
            Column(
                modifier = modifier
                    .weight(1f)
                    .graphicsLayer {
                        scaleX = selectedScale
                        scaleY = selectedScale
                    }
                    .clip(RoundedCornerShape(NuvioTokens.Radius.full))
                    .background(selectedBgColor)
                    .selectable(
                        selected = selected,
                        enabled = true,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                    .padding(vertical = NuvioTokens.Space.s6),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    modifier = Modifier
                        .size(28.dp)
                        .then(if (selected) Modifier.gradientMask(palette.accentBrush()) else Modifier),
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = if (selected) Color.White else iconColor,
                )
                NavItemLabel(
                    label = label,
                    reserveLabelSpace = reserveLabelSpace,
                    labelFraction = labelFraction,
                    iconColor = iconColor,
                    selected = selected,
                )
            }
        }
    }

    @Composable
    override fun NavItem(
        selected: Boolean,
        onClick: () -> Unit,
        icon: DrawableResource,
        contentDescription: String?,
        modifier: Modifier,
        label: String?,
        reserveLabelSpace: Boolean,
    ) {
        val tokens = MaterialTheme.nuvio
        val palette = ThemeColors.getColorPalette(MaterialTheme.appTheme)
        val iconColor by animateColorAsState(
            targetValue = if (selected) tokens.colors.accent else tokens.colors.textMuted,
            label = "nav_icon_color",
        )
        val selectedBgColor by animateColorAsState(
            targetValue = if (selected && !usesSharedSelectionIndicator) {
                tokens.colors.accent.copy(alpha = NuvioTokens.Opacity.selected)
            } else {
                Color.Transparent
            },
            label = "nav_bg_color",
        )
        val selectedScale by animateFloatAsState(
            targetValue = if (selected && usesSharedSelectionIndicator) 1.08f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
            label = "nav_selection_scale",
        )

        with(rowScope) {
            Column(
                modifier = modifier
                    .weight(1f)
                    .graphicsLayer {
                        scaleX = selectedScale
                        scaleY = selectedScale
                    }
                    .clip(RoundedCornerShape(NuvioTokens.Radius.full))
                    .background(selectedBgColor)
                    .selectable(
                        selected = selected,
                        enabled = true,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                    .padding(vertical = NuvioTokens.Space.s6),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    modifier = Modifier
                        .size(28.dp)
                        .then(if (selected) Modifier.gradientMask(palette.accentBrush()) else Modifier),
                    painter = painterResource(icon),
                    contentDescription = contentDescription,
                    tint = if (selected) Color.White else iconColor,
                )
                NavItemLabel(
                    label = label,
                    reserveLabelSpace = reserveLabelSpace,
                    labelFraction = labelFraction,
                    iconColor = iconColor,
                    selected = selected,
                )
            }
        }
    }

    @Composable
    override fun NavItem(
        selected: Boolean,
        onClick: () -> Unit,
        modifier: Modifier,
        label: String?,
        reserveLabelSpace: Boolean,
        content: @Composable () -> Unit,
    ) {
        val tokens = MaterialTheme.nuvio
        val selectedBgColor by animateColorAsState(
            targetValue = if (selected && !usesSharedSelectionIndicator) {
                tokens.colors.accent.copy(alpha = NuvioTokens.Opacity.selected)
            } else {
                Color.Transparent
            },
            label = "nav_bg_color",
        )
        val iconColor by animateColorAsState(
            targetValue = if (selected) tokens.colors.accent else tokens.colors.textMuted,
            label = "nav_icon_color",
        )
        val selectedScale by animateFloatAsState(
            targetValue = if (selected && usesSharedSelectionIndicator) 1.08f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
            label = "nav_selection_scale",
        )

        with(rowScope) {
            Column(
                modifier = modifier
                    .weight(1f)
                    .graphicsLayer {
                        scaleX = selectedScale
                        scaleY = selectedScale
                    }
                    .clip(RoundedCornerShape(NuvioTokens.Radius.full))
                    .background(selectedBgColor)
                    .selectable(
                        selected = selected,
                        enabled = true,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                    .padding(vertical = NuvioTokens.Space.s6),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                content()
                NavItemLabel(
                    label = label,
                    reserveLabelSpace = reserveLabelSpace,
                    labelFraction = labelFraction,
                    iconColor = iconColor,
                    selected = selected,
                )
            }
        }
    }
}

@Composable
private fun NavItemLabel(
    label: String?,
    reserveLabelSpace: Boolean,
    labelFraction: Float,
    iconColor: Color,
    selected: Boolean,
) {
    if (!reserveLabelSpace || labelFraction <= 0f) return
    Spacer(modifier = Modifier.height(NuvioTokens.Space.s3 * labelFraction))
    Box(
        modifier = Modifier
            .height(NuvioTokens.Space.s14 * labelFraction)
            .alpha(if (label == null) 0f else labelFraction),
    ) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = NuvioTokens.Type.labelXs,
                    lineHeight = NuvioTokens.LineHeight.labelXs,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = iconColor,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}


/**
 * Classic flat navigation bar — the original pre-pill implementation.
 * No floating pill, no labels, no scroll behavior. Simple icon row with a top divider.
 */
@Composable
fun NuvioClassicNavigationBar(
    modifier: Modifier = Modifier,
    content: @Composable NuvioNavigationBarScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(modifier.fillMaxWidth()) {
        androidx.compose.material3.HorizontalDivider(
            thickness = NuvioTokens.Space.hairline,
            color = tokens.colors.borderDefault,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(nuvioBottomNavigationBarInsets().asPaddingValues())
                .padding(horizontal = NuvioTokens.Space.s4, vertical = nuvioBottomNavigationExtraVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap, Alignment.CenterHorizontally),
        ) {
            NuvioClassicNavigationBarScopeImpl(this).content()
        }
    }
}

private class NuvioClassicNavigationBarScopeImpl(
    private val rowScope: androidx.compose.foundation.layout.RowScope,
) : NuvioNavigationBarScope {

    @Composable
    override fun NavItem(
        selected: Boolean,
        onClick: () -> Unit,
        icon: ImageVector,
        contentDescription: String?,
        modifier: Modifier,
        label: String?,
        reserveLabelSpace: Boolean,
    ) {
        val tokens = MaterialTheme.nuvio
        val palette = ThemeColors.getColorPalette(MaterialTheme.appTheme)
        val iconColor by animateColorAsState(
            targetValue = if (selected) tokens.colors.accent else tokens.colors.textMuted,
            label = "classic_nav_icon_color",
        )
        with(rowScope) {
            Icon(
                modifier = modifier
                    .widthIn(max = tokens.components.navItemMaxWidth)
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .clip(tokens.components.navItemShape)
                    .selectable(
                        selected = selected,
                        enabled = true,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                    .padding(NuvioTokens.Space.s10)
                    .size(tokens.components.navIconSize)
                    .then(if (selected) Modifier.gradientMask(palette.accentBrush()) else Modifier),
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (selected) Color.White else iconColor,
            )
        }
    }

    @Composable
    override fun NavItem(
        selected: Boolean,
        onClick: () -> Unit,
        icon: DrawableResource,
        contentDescription: String?,
        modifier: Modifier,
        label: String?,
        reserveLabelSpace: Boolean,
    ) {
        val tokens = MaterialTheme.nuvio
        val palette = ThemeColors.getColorPalette(MaterialTheme.appTheme)
        val iconColor by animateColorAsState(
            targetValue = if (selected) tokens.colors.accent else tokens.colors.textMuted,
            label = "classic_nav_icon_color",
        )
        with(rowScope) {
            Icon(
                modifier = modifier
                    .widthIn(max = tokens.components.navItemMaxWidth)
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .clip(tokens.components.navItemShape)
                    .selectable(
                        selected = selected,
                        enabled = true,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                    .padding(NuvioTokens.Space.s10)
                    .size(tokens.components.navIconSize)
                    .then(if (selected) Modifier.gradientMask(palette.accentBrush()) else Modifier),
                painter = painterResource(icon),
                contentDescription = contentDescription,
                tint = if (selected) Color.White else iconColor,
            )
        }
    }

    @Composable
    override fun NavItem(
        selected: Boolean,
        onClick: () -> Unit,
        modifier: Modifier,
        label: String?,
        reserveLabelSpace: Boolean,
        content: @Composable () -> Unit,
    ) {
        val tokens = MaterialTheme.nuvio
        with(rowScope) {
            Box(
                modifier = modifier
                    .widthIn(max = tokens.components.navItemMaxWidth)
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .clip(tokens.components.navItemShape)
                    .selectable(
                        selected = selected,
                        enabled = true,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                    .padding(NuvioTokens.Space.s10),
                contentAlignment = Alignment.Center,
            ) {
                content()
            }
        }
    }
}
