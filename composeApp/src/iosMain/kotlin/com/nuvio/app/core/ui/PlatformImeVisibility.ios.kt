package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.delay
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIKeyboardDidHideNotification
import platform.UIKit.UIKeyboardWillHideNotification
import platform.UIKit.UIKeyboardWillShowNotification

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun rememberReliableImeVisibility(windowInsetsVisible: Boolean): Boolean {
    val viewController = LocalUIViewController.current
    var nativeVisibility by remember { mutableStateOf<Boolean?>(null) }
    var appActive by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        val notificationCenter = NSNotificationCenter.defaultCenter
        val showObserver = notificationCenter.addObserverForName(
            name = UIKeyboardWillShowNotification,
            `object` = null,
            queue = null,
        ) { nativeVisibility = true }
        val willHideObserver = notificationCenter.addObserverForName(
            name = UIKeyboardWillHideNotification,
            `object` = null,
            queue = null,
        ) { nativeVisibility = false }
        val didHideObserver = notificationCenter.addObserverForName(
            name = UIKeyboardDidHideNotification,
            `object` = null,
            queue = null,
        ) { nativeVisibility = false }
        val didBecomeActiveObserver = notificationCenter.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = null,
        ) { appActive = true }
        val willResignActiveObserver = notificationCenter.addObserverForName(
            name = UIApplicationWillResignActiveNotification,
            `object` = null,
            queue = null,
        ) { appActive = false }
        onDispose {
            notificationCenter.removeObserver(showObserver)
            notificationCenter.removeObserver(willHideObserver)
            notificationCenter.removeObserver(didHideObserver)
            notificationCenter.removeObserver(didBecomeActiveObserver)
            notificationCenter.removeObserver(willResignActiveObserver)
        }
    }

    LaunchedEffect(windowInsetsVisible, appActive, viewController) {
        while (appActive && windowInsetsVisible) {
            val bottomSafeArea = viewController.view.safeAreaInsets.useContents { bottom }
            val keyboardLayoutHeight = viewController.view.keyboardLayoutGuide.layoutFrame.useContents {
                size.height
            }
            nativeVisibility = keyboardLayoutOccludesContent(
                layoutHeight = keyboardLayoutHeight,
                bottomSafeArea = bottomSafeArea,
            )
            delay(KeyboardLayoutPollMillis)
        }
    }

    return reconciledIosImeVisibility(
        windowInsetsVisible = windowInsetsVisible,
        nativeVisibility = nativeVisibility,
    )
}

private const val KeyboardLayoutPollMillis = 100L
