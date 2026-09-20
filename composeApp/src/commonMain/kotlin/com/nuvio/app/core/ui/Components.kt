package com.nuvio.app.core.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_back
import nuvio.composeapp.generated.resources.action_ok
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.nuvio.app.navigation.LocalNativeNavigationBarHidden
import com.nuvio.app.navigation.LocalUseNativeNavigation
import kotlin.math.max

@Composable
fun NuvioScreen(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = MaterialTheme.nuvio.spacing.screenHorizontal,
    topPadding: Dp? = null,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val rootInsets = LocalNativeRootContentInsets.current
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(tokens.colors.background),
        contentPadding = PaddingValues(
            start = horizontalPadding + rootInsets.start.dp,
            top = topPadding ?: tokens.spacing.screenTop + statusBarTop + nuvioPlatformExtraTopPadding +
                LocalNuvioTopNavigationOverlayPadding.current,
            end = horizontalPadding + rootInsets.end.dp,
            bottom = nuvioSafeBottomPadding(tokens.spacing.screenBottom),
        ),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
        content = content,
    )
}

internal fun Modifier.nuvioConsumePointerEvents(): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Final).changes.forEach { change ->
                    change.consume()
                }
            }
        }
    }

@Composable
fun NuvioSurfaceCard(
    modifier: Modifier = Modifier,
    tonalElevation: Int = 0,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = tokens.colors.surface,
        shape = tokens.shapes.card,
        tonalElevation = tonalElevation.dp,
        shadowElevation = tokens.elevation.flat,
    ) {
        Column(
            modifier = Modifier.padding(tokens.spacing.cardPadding),
            content = content,
        )
    }
}

@Composable
fun NuvioScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    includeStatusBarPadding: Boolean = true,
    topPadding: Dp? = null,
    onBack: (() -> Unit)? = null,
    actionsLayout: NuvioScreenHeaderActionsLayout = NuvioScreenHeaderActionsLayout.Inline,
    actions: @Composable RowScope.() -> Unit = {},
    compactActions: (@Composable RowScope.() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val nativeDetailNavigation = LocalUseNativeNavigation.current &&
        !LocalNativeNavigationBarHidden.current &&
        onBack != null
    if (nativeDetailNavigation) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(bottom = NuvioTokens.Space.s4),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
        return
    }
    val resolvedTopPadding = topPadding ?: if (includeStatusBarPadding) statusBarTop else NuvioTokens.Space.none
    Box(
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .matchParentSize()
                .background(tokens.colors.background)
                .nuvioConsumePointerEvents(),
        ) {}
        @Composable
        fun HeaderTitle(modifier: Modifier = Modifier) {
            Row(
                modifier = modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back),
                            tint = tokens.colors.textPrimary,
                        )
                    }
                }
                AnimatedContent(
                    targetState = title,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "screen_header_title",
                ) { currentTitle ->
                    Text(
                        text = currentTitle,
                        style = MaterialTheme.typography.displayLarge,
                        color = tokens.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (actionsLayout == NuvioScreenHeaderActionsLayout.Adaptive) {
            val titleStyle = MaterialTheme.typography.displayLarge
            val textMeasurer = rememberTextMeasurer()
            val titleNaturalWidth = textMeasurer.measure(
                text = AnnotatedString(title),
                style = titleStyle,
                maxLines = 1,
            ).size.width
            val backWidth = if (onBack == null) 0 else with(androidx.compose.ui.platform.LocalDensity.current) {
                (NuvioTokens.Space.s48 + tokens.spacing.controlGap).roundToPx()
            }
            val minimumInlineTitleWidth = with(androidx.compose.ui.platform.LocalDensity.current) {
                NuvioTokens.Space.s96.roundToPx()
            }
            val inlineSpacing = with(androidx.compose.ui.platform.LocalDensity.current) {
                NuvioTokens.Space.s2.roundToPx()
            }
            val stackedSpacing = with(androidx.compose.ui.platform.LocalDensity.current) {
                NuvioTokens.Space.s4.roundToPx()
            }

            SubcomposeLayout(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = resolvedTopPadding, bottom = NuvioTokens.Space.s4),
            ) { constraints ->
                val looseConstraints = constraints.copy(
                    minWidth = 0,
                    minHeight = 0,
                    maxWidth = Constraints.Infinity,
                )
                val fullActionsPlaceable = subcompose(NuvioHeaderSlot.FullActions) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }.single().measure(looseConstraints)
                val compactActionsPlaceable = compactActions?.let { compactContent ->
                    subcompose(NuvioHeaderSlot.CompactActions) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
                            verticalAlignment = Alignment.CenterVertically,
                            content = compactContent,
                        )
                    }.single().measure(looseConstraints)
                }
                val presentation = resolveAdaptiveHeaderPresentation(
                    availableWidthPx = constraints.maxWidth,
                    naturalTitleWidthPx = titleNaturalWidth + backWidth,
                    fullActionsWidthPx = fullActionsPlaceable.width,
                    compactActionsWidthPx = compactActionsPlaceable?.width,
                    minimumInlineTitleWidthPx = minimumInlineTitleWidth,
                    spacingPx = inlineSpacing,
                )
                val selectedActions = when (presentation) {
                    NuvioAdaptiveHeaderPresentation.FullInline -> fullActionsPlaceable
                    NuvioAdaptiveHeaderPresentation.CompactInline,
                    NuvioAdaptiveHeaderPresentation.CompactStacked,
                    -> compactActionsPlaceable ?: fullActionsPlaceable
                }
                val isStacked = presentation == NuvioAdaptiveHeaderPresentation.CompactStacked
                val actionSpacing = if (selectedActions.width == 0) 0 else inlineSpacing
                val titleMaxWidth = if (isStacked) {
                    constraints.maxWidth
                } else {
                    (constraints.maxWidth - selectedActions.width - actionSpacing).coerceAtLeast(0)
                }
                val titlePlaceable = subcompose(NuvioHeaderSlot.Title) {
                    HeaderTitle()
                }.single().measure(
                    constraints.copy(
                        minWidth = 0,
                        minHeight = 0,
                        maxWidth = titleMaxWidth,
                    ),
                )
                val contentHeight = if (isStacked) {
                    titlePlaceable.height + stackedSpacing + selectedActions.height
                } else {
                    max(titlePlaceable.height, selectedActions.height)
                }

                layout(constraints.maxWidth, contentHeight) {
                    if (isStacked) {
                        titlePlaceable.placeRelative(0, 0)
                        selectedActions.placeRelative(
                            x = (constraints.maxWidth - selectedActions.width).coerceAtLeast(0),
                            y = titlePlaceable.height + stackedSpacing,
                        )
                    } else {
                        titlePlaceable.placeRelative(
                            x = 0,
                            y = contentHeight - titlePlaceable.height,
                        )
                        selectedActions.placeRelative(
                            x = (constraints.maxWidth - selectedActions.width).coerceAtLeast(0),
                            y = contentHeight - selectedActions.height,
                        )
                    }
                }
            }
        } else if (actionsLayout == NuvioScreenHeaderActionsLayout.Stacked) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = resolvedTopPadding, bottom = NuvioTokens.Space.s4),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
            ) {
                HeaderTitle()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = resolvedTopPadding, bottom = NuvioTokens.Space.s4),
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
                verticalAlignment = Alignment.Bottom,
            ) {
                HeaderTitle(modifier = Modifier.weight(1f))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}

enum class NuvioScreenHeaderActionsLayout {
    Adaptive,
    Inline,
    Stacked,
}

internal enum class NuvioAdaptiveHeaderPresentation {
    FullInline,
    CompactInline,
    CompactStacked,
}

internal fun resolveAdaptiveHeaderPresentation(
    availableWidthPx: Int,
    naturalTitleWidthPx: Int,
    fullActionsWidthPx: Int,
    compactActionsWidthPx: Int?,
    minimumInlineTitleWidthPx: Int,
    spacingPx: Int,
): NuvioAdaptiveHeaderPresentation {
    val fullSpacing = if (fullActionsWidthPx == 0) 0 else spacingPx
    if (naturalTitleWidthPx + fullSpacing + fullActionsWidthPx <= availableWidthPx) {
        return NuvioAdaptiveHeaderPresentation.FullInline
    }

    val compactWidth = compactActionsWidthPx ?: fullActionsWidthPx
    val compactSpacing = if (compactWidth == 0) 0 else spacingPx
    return if (minimumInlineTitleWidthPx + compactSpacing + compactWidth <= availableWidthPx) {
        NuvioAdaptiveHeaderPresentation.CompactInline
    } else {
        NuvioAdaptiveHeaderPresentation.CompactStacked
    }
}

private enum class NuvioHeaderSlot {
    Title,
    FullActions,
    CompactActions,
}

@Composable
fun NuvioSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.nuvio.colors.textMuted,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
fun NuvioActionLabel(
    text: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Text(
        text = text,
        modifier = modifier.then(
            if (onClick != null) {
                Modifier.clickable(onClick = onClick)
            } else {
                Modifier
            }
        ),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.nuvio.colors.accent,
    )
}

@Composable
fun NuvioIconActionButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.nuvio.colors.textPrimary,
    onClick: () -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    IconButton(
        modifier = modifier
            .background(
                color = tokens.colors.background.copy(alpha = 0.001f),
                shape = tokens.shapes.avatar,
            ),
        onClick = onClick,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
        )
    }
}

@Composable
fun NuvioQuietActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector? = null,
    contentDescription: String? = label,
    enabled: Boolean = true,
    tone: NuvioQuietActionTone = NuvioQuietActionTone.Neutral,
    style: NuvioQuietActionStyle = NuvioQuietActionStyle.Compact,
) {
    require(label != null || icon != null)
    val tokens = MaterialTheme.nuvio
    val iconOnly = label == null
    val destructiveColor = ThemeColors.Crimson.secondary
    val selected = style == NuvioQuietActionStyle.Selected
    val containerColor = when {
        selected && tone == NuvioQuietActionTone.Neutral -> MaterialTheme.colorScheme.primaryContainer
        tone == NuvioQuietActionTone.Neutral -> tokens.colors.overlayHover
        else -> destructiveColor.copy(alpha = tokens.opacity.selected)
    }
    val contentColor = when {
        selected && tone == NuvioQuietActionTone.Neutral -> MaterialTheme.colorScheme.onPrimaryContainer
        tone == NuvioQuietActionTone.Neutral -> tokens.colors.textSecondary
        else -> destructiveColor
    }
    val borderColor = when {
        selected && tone == NuvioQuietActionTone.Neutral ->
            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        tone == NuvioQuietActionTone.Neutral -> tokens.colors.borderStrong
        else -> destructiveColor.copy(alpha = tokens.opacity.medium)
    }.let { color ->
        if (enabled) color else color.copy(alpha = color.alpha * tokens.opacity.disabled)
    }
    val resolvedContainerColor = if (enabled) {
        containerColor
    } else {
        containerColor.copy(alpha = containerColor.alpha * tokens.opacity.disabled)
    }
    val resolvedContentColor = if (enabled) contentColor else tokens.colors.textDisabled
    val border = if (style == NuvioQuietActionStyle.Outlined || selected) {
        BorderStroke(tokens.borders.thin, borderColor)
    } else {
        null
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val visibleContainerColor = if (enabled && isPressed) {
        tokens.colors.overlayPressed.compositeOver(resolvedContainerColor)
    } else {
        resolvedContainerColor
    }
    Box(
        modifier = modifier.then(
            if (iconOnly) {
                Modifier.size(NuvioTokens.Space.s40 + NuvioTokens.Space.s4)
            } else {
                Modifier.height(NuvioTokens.Space.s40 + NuvioTokens.Space.s4)
            },
        ).clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = if (iconOnly) {
                Modifier.size(NuvioTokens.Space.s32)
            } else {
                Modifier.height(NuvioTokens.Space.s32)
            },
            shape = tokens.shapes.chip,
            color = visibleContainerColor,
            contentColor = resolvedContentColor,
            border = border,
        ) {
            Row(
                modifier = if (iconOnly) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.padding(horizontal = NuvioTokens.Space.s10)
                },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = contentDescription,
                        modifier = Modifier.size(
                            if (iconOnly) NuvioTokens.Icon.md else NuvioTokens.Icon.sm,
                        ),
                    )
                }
                if (icon != null && label != null) {
                    Spacer(Modifier.width(NuvioTokens.Space.s6))
                }
                if (label != null) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

enum class NuvioQuietActionTone {
    Neutral,
    Destructive,
}

enum class NuvioQuietActionStyle {
    Compact,
    Outlined,
    Selected,
}

@Composable
fun NuvioBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.nuvio.shapes.avatar,
    containerColor: Color = MaterialTheme.nuvio.colors.surface,
    contentColor: Color = MaterialTheme.nuvio.colors.textPrimary,
    buttonSize: Dp = NuvioTokens.Space.s40,
    iconSize: Dp = NuvioTokens.Icon.md,
    contentDescription: String = stringResource(Res.string.action_back),
    hideWhenNativeNavigationVisible: Boolean = true,
) {
    if (
        hideWhenNativeNavigationVisible &&
        LocalUseNativeNavigation.current &&
        !LocalNativeNavigationBarHidden.current
    ) return

    Box(
        modifier = modifier
            .size(buttonSize)
            .clip(shape)
            .background(containerColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
fun NuvioPrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(NuvioTokens.Space.s48 + NuvioTokens.Space.s4),
        enabled = enabled,
        shape = tokens.shapes.button,
        colors = ButtonDefaults.buttonColors(
            containerColor = tokens.colors.accent,
            contentColor = tokens.colors.onAccent,
            disabledContainerColor = tokens.colors.accent.copy(alpha = tokens.opacity.disabled),
            disabledContentColor = tokens.colors.onAccent.copy(alpha = tokens.opacity.disabled),
        ),
    ) {
        AnimatedContent(
            targetState = text,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "buttonText",
        ) { animatedText ->
            Text(
                text = animatedText,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun NuvioInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable (() -> Unit))? = null,
) {
    val tokens = MaterialTheme.nuvio
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(NuvioTokens.Radius.lg),
        placeholder = {
            Text(
                text = placeholder,
                color = tokens.colors.textMuted,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = tokens.colors.textPrimary),
        trailingIcon = trailingContent,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = tokens.colors.borderFocus,
            unfocusedBorderColor = tokens.colors.borderDefault,
            focusedContainerColor = tokens.colors.surfaceCard,
            unfocusedContainerColor = tokens.colors.surfaceCard,
            cursorColor = tokens.colors.accent,
        ),
    )
}

@Composable
fun NuvioInfoBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = modifier
            .background(
                color = tokens.colors.surfaceCard,
                shape = tokens.shapes.chip,
            )
            .padding(horizontal = NuvioTokens.Space.s10, vertical = NuvioTokens.Space.s6),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = tokens.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun NuvioInlineMetadata(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textMuted,
        )
        Spacer(modifier = Modifier.width(NuvioTokens.Space.s6))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textPrimary,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun NuvioStatusModal(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    isVisible: Boolean,
    isBusy: Boolean = false,
    confirmText: String = stringResource(Res.string.action_ok),
    dismissText: String? = null,
    onConfirm: () -> Unit,
    onDismiss: (() -> Unit)? = null,
) {
    if (!isVisible) return
    val tokens = MaterialTheme.nuvio

    BasicAlertDialog(
        onDismissRequest = {
            if (!isBusy) {
                onDismiss?.invoke() ?: onConfirm()
            }
        },
    ) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = tokens.colors.surfaceDialog,
            shape = tokens.shapes.dialog,
        ) {
            Column(
                modifier = Modifier.padding(tokens.spacing.dialogPadding),
            ) {
                if (isBusy) {
                    NuvioLoadingIndicator(
                        color = tokens.colors.accent,
                    )
                    Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(tokens.spacing.controlGap))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textMuted,
                )
                Spacer(modifier = Modifier.height(NuvioTokens.Space.s18))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (!isBusy && dismissText != null && onDismiss != null) {
                        Button(
                            onClick = onDismiss,
                            shape = tokens.shapes.button,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = tokens.colors.surfaceCard,
                                contentColor = tokens.colors.textPrimary,
                            ),
                        ) {
                            Text(dismissText)
                        }
                        Spacer(modifier = Modifier.width(NuvioTokens.Space.s10))
                    }
                    Button(
                        onClick = onConfirm,
                        enabled = !isBusy,
                        shape = tokens.shapes.button,
                    ) {
                        Text(confirmText)
                    }
                }
            }
        }
    }
}

@Composable
fun NuvioToastHost(
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val toast by NuvioToastController.currentToast.collectAsState()
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val visibilityState = remember { MutableTransitionState(false) }
    var renderedToast by remember { mutableStateOf<NuvioToastMessage?>(null) }

    LaunchedEffect(toast?.id) {
        val currentToast = toast
        if (currentToast != null) {
            renderedToast = currentToast
            visibilityState.targetState = true
            delay(currentToast.durationMillis)
            NuvioToastController.dismiss(currentToast.id)
        } else {
            visibilityState.targetState = false
        }
    }

    LaunchedEffect(
        visibilityState.currentState,
        visibilityState.targetState,
        visibilityState.isIdle,
    ) {
        if (visibilityState.isIdle && !visibilityState.currentState && !visibilityState.targetState) {
            renderedToast = null
        }
    }

    AnimatedVisibility(
        visibleState = visibilityState,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
    ) {
        val currentToast = renderedToast ?: return@AnimatedVisibility
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = statusBarTop + tokens.spacing.listGap)
                .padding(horizontal = tokens.spacing.screenHorizontal),
            contentAlignment = Alignment.TopCenter,
        ) {
            Surface(
                modifier = currentToast.onClick?.let { onClick ->
                    Modifier.clickable {
                        NuvioToastController.dismiss(currentToast.id)
                        onClick()
                    }
                } ?: Modifier,
                shape = RoundedCornerShape(NuvioTokens.Radius.xl),
                color = tokens.colors.surfacePopover,
                tonalElevation = tokens.elevation.raised,
                shadowElevation = tokens.elevation.overlay,
            ) {
                Text(
                    text = currentToast.message,
                    modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s12),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textPrimary,
                )
            }
        }
    }
}

data class NuvioToastMessage(
    val id: Long,
    val message: String,
    val durationMillis: Long,
    val onClick: (() -> Unit)? = null,
)

object NuvioToastController {
    private val _currentToast = MutableStateFlow<NuvioToastMessage?>(null)
    val currentToast = _currentToast.asStateFlow()
    private var nextToastId = 0L

    fun show(
        message: String,
        durationMillis: Long = 2500L,
        onClick: (() -> Unit)? = null,
    ) {
        nextToastId += 1L
        _currentToast.value = NuvioToastMessage(
            id = nextToastId,
            message = message,
            durationMillis = durationMillis,
            onClick = onClick,
        )
    }

    fun dismiss(id: Long? = null) {
        val activeToast = _currentToast.value ?: return
        if (id == null || activeToast.id == id) {
            _currentToast.value = null
        }
    }
}
