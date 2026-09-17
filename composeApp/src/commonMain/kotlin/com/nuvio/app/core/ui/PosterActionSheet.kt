package com.nuvio.app.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.unit.Dp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.episodes_cd_watched
import org.jetbrains.compose.resources.stringResource

@Composable
fun NuvioWatchedBadge(
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val palette = ThemeColors.getColorPalette(MaterialTheme.appTheme)
    Box(
        modifier = modifier
            .size(NuvioTokens.Icon.md)
            .clip(tokens.shapes.avatar)
            .background(palette.accentBrush()),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = stringResource(Res.string.episodes_cd_watched),
            tint = palette.onSecondary,
            modifier = Modifier.size(NuvioTokens.Icon.xs),
        )
    }
}

@Composable
fun NuvioAnimatedWatchedBadge(
    isVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        NuvioWatchedBadge()
    }
}

/**
 * Standard watched treatment for poster-shaped surfaces. Poster callers use
 * this top-start placement so status remains clear of top-end menus and the
 * bottom-end selection mark. Episode rows and player lists intentionally call
 * [NuvioAnimatedWatchedBadge] directly because their inline thumbnails are not
 * poster overlays and own their badge alignment.
 */
@Composable
fun BoxScope.NuvioPosterWatchedOverlay(
    isWatched: Boolean,
    modifier: Modifier = Modifier,
    padding: Dp = NuvioTokens.Space.s6,
) {
    NuvioAnimatedWatchedBadge(
        isVisible = isWatched,
        modifier = modifier
            .align(Alignment.TopStart)
            .padding(padding),
    )
}

/**
 * Draws the standard poster selection mark in the corner opposite the watched
 * badge and the poster menu. The solid accent keeps the on-accent icon
 * predictable across every theme, while the dark edge separates bright
 * accents from light artwork.
 */
@Composable
fun BoxScope.NuvioPosterSelectionOverlay(
    state: NuvioPosterSelectionState,
    modifier: Modifier = Modifier,
    padding: Dp = NuvioTokens.Space.s6,
) {
    val tokens = MaterialTheme.nuvio
    AnimatedVisibility(
        visible = state != NuvioPosterSelectionState.None,
        enter = fadeIn() + scaleIn(initialScale = 0.82f),
        exit = fadeOut() + scaleOut(targetScale = 0.82f),
        modifier = modifier
            .align(Alignment.BottomEnd)
            .padding(padding),
    ) {
        Box(
            modifier = Modifier
                .size(NuvioTokens.Icon.md)
                .clip(tokens.shapes.avatar)
                .background(tokens.colors.accent)
                .border(tokens.borders.thin, tokens.colors.overlayScrim, tokens.shapes.avatar),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (state == NuvioPosterSelectionState.Full) {
                    Icons.Default.Check
                } else {
                    Icons.Default.Remove
                },
                contentDescription = null,
                tint = tokens.colors.onAccent,
                modifier = Modifier.size(NuvioTokens.Icon.xs),
            )
        }
    }
}

/**
 * Paints status rings inside the poster bounds, so selection and keyboard
 * focus never resize a card or change its grid geometry.
 */
@Composable
fun Modifier.nuvioPosterStateOutline(
    selectionState: NuvioPosterSelectionState,
    focused: Boolean,
    cornerRadius: Dp,
): Modifier {
    val tokens = MaterialTheme.nuvio
    val selectionColor = tokens.colors.borderSelected
    val selectionSeparatorColor = tokens.colors.overlayScrim
    val focusColor = tokens.colors.borderFocus
    val selectionStroke = tokens.borders.medium
    val separatorStroke = tokens.borders.medium + tokens.borders.thin + tokens.borders.thin
    val focusStroke = tokens.borders.medium
    val focusInset = NuvioTokens.Space.s5

    return drawWithCache {
        val radiusPx = cornerRadius.toPx()
        val selectionStrokePx = selectionStroke.toPx()
        val separatorStrokePx = separatorStroke.toPx()
        val focusStrokePx = focusStroke.toPx()
        val focusInsetPx = focusInset.toPx()

        onDrawWithContent {
            drawContent()
            if (selectionState != NuvioPosterSelectionState.None) {
                val separatorInset = separatorStrokePx / 2f
                drawRoundRect(
                    color = selectionSeparatorColor,
                    topLeft = Offset(separatorInset, separatorInset),
                    size = Size(
                        width = (size.width - separatorStrokePx).coerceAtLeast(0f),
                        height = (size.height - separatorStrokePx).coerceAtLeast(0f),
                    ),
                    cornerRadius = CornerRadius(
                        x = (radiusPx - separatorInset).coerceAtLeast(0f),
                        y = (radiusPx - separatorInset).coerceAtLeast(0f),
                    ),
                    style = Stroke(width = separatorStrokePx),
                )
                val selectionInset = selectionStrokePx / 2f
                drawRoundRect(
                    color = selectionColor,
                    topLeft = Offset(selectionInset, selectionInset),
                    size = Size(
                        width = (size.width - selectionStrokePx).coerceAtLeast(0f),
                        height = (size.height - selectionStrokePx).coerceAtLeast(0f),
                    ),
                    cornerRadius = CornerRadius(
                        x = (radiusPx - selectionInset).coerceAtLeast(0f),
                        y = (radiusPx - selectionInset).coerceAtLeast(0f),
                    ),
                    style = Stroke(width = selectionStrokePx),
                )
            }
            if (focused) {
                val inset = (if (selectionState == NuvioPosterSelectionState.None) 0f else focusInsetPx) +
                    focusStrokePx / 2f
                val focusSize = Size(
                    width = (size.width - inset * 2f).coerceAtLeast(0f),
                    height = (size.height - inset * 2f).coerceAtLeast(0f),
                )
                drawRoundRect(
                    color = focusColor,
                    topLeft = Offset(inset, inset),
                    size = focusSize,
                    cornerRadius = CornerRadius(
                        x = (radiusPx - inset).coerceAtLeast(0f),
                        y = (radiusPx - inset).coerceAtLeast(0f),
                    ),
                    style = Stroke(width = focusStrokePx),
                )
            }
        }
    }
}
