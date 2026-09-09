package com.nuvio.app.features.player

import android.content.SharedPreferences
import java.lang.reflect.Proxy

internal actual fun withIsolatedTrackPreferences(block: (String) -> Unit) {
    val values = mutableMapOf<String, Any>()
    val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
        arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
        when (method.name) {
            "putString", "putBoolean" -> { values[args[0] as String] = args[1]; proxy }
            "remove" -> { values.remove(args[0] as String); proxy }
            "apply" -> null
            else -> error("Unexpected editor call: ${method.name}")
        }
    } as SharedPreferences.Editor
    val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when (method.name) {
            "getString", "getBoolean" -> values[args[0] as String] ?: args[1]
            "contains" -> values.containsKey(args[0] as String)
            "edit" -> editor
            else -> error("Unexpected preferences call: ${method.name}")
        }
    } as SharedPreferences
    val field = PlayerTrackPreferenceStorage::class.java.getDeclaredField("preferences")
    field.isAccessible = true
    val previous = field.get(PlayerTrackPreferenceStorage)
    try {
        field.set(PlayerTrackPreferenceStorage, preferences)
        block("subtitle-restoration-test")
    } finally {
        field.set(PlayerTrackPreferenceStorage, previous)
    }
}
