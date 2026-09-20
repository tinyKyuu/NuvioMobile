package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class NativeRootContentLayoutTest {
    @Test
    fun `hosts without native overlays keep existing layout`() {
        assertEquals(NativeRootContentInsets(), NativeRootContentLayout().insets.value)
    }

    @Test
    fun `side controls use asymmetric clearance without adding a bottom dock`() {
        val layout = NativeRootContentLayout()
        layout.update(start = 0.0, end = 96.0, bottomDock = 0.0)
        assertEquals(NativeRootContentInsets(end = 96f), layout.insets.value)
    }

    @Test
    fun `rotation replaces side clearance with bottom scroll clearance`() {
        val layout = NativeRootContentLayout()
        layout.update(start = 0.0, end = 96.0, bottomDock = 0.0)
        layout.update(start = 0.0, end = 0.0, bottomDock = 76.0)
        assertEquals(NativeRootContentInsets(bottomDock = 76f), layout.insets.value)
    }

    @Test
    fun `leading bars retain their clearance independently of trailing bars`() {
        val layout = NativeRootContentLayout()
        layout.update(start = 84.5, end = 20.0, bottomDock = 0.0)
        assertEquals(NativeRootContentInsets(start = 84.5f, end = 20f), layout.insets.value)
    }

    @Test
    fun `controllers do not share inset state`() {
        val first = NativeRootContentLayout()
        val second = NativeRootContentLayout()
        first.update(start = 0.0, end = 96.0, bottomDock = 0.0)
        assertEquals(NativeRootContentInsets(), second.insets.value)
    }

    @Test
    fun `invalid native measurements cannot enter Compose padding`() {
        val layout = NativeRootContentLayout()
        layout.update(start = Double.NaN, end = Double.POSITIVE_INFINITY, bottomDock = -1.0)
        assertEquals(NativeRootContentInsets(), layout.insets.value)
        layout.update(start = Double.NEGATIVE_INFINITY, end = Double.MAX_VALUE, bottomDock = 76.0)
        assertEquals(NativeRootContentInsets(bottomDock = 76f), layout.insets.value)
    }
}
