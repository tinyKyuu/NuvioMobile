package com.nuvio.app.features.downloads

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

internal actual object DownloadsRequestStorage {
    private const val keychainService = "com.nuvio.media.download-requests"

    @OptIn(ExperimentalForeignApi::class)
    actual fun loadPayload(downloadId: String): String? = withKeychainQuery(downloadId) { query ->
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            if (status == errSecItemNotFound) return@memScoped null
            if (status != errSecSuccess) return@memScoped null
            val data: CFDataRef = result.value?.reinterpret() ?: return@memScoped null
            try {
                val length = CFDataGetLength(data).toInt()
                val bytes = CFDataGetBytePtr(data) ?: return@memScoped null
                ByteArray(length) { index -> bytes[index].toByte() }.decodeToString()
            } finally {
                CFRelease(data)
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun savePayload(downloadId: String, payload: String): Boolean {
        remove(downloadId)
        if (payload.isBlank()) return true
        return withKeychainQuery(downloadId) { query ->
            val bytes = payload.encodeToByteArray().toUByteArray()
            val data = CFDataCreate(null, bytes.refTo(0), bytes.size.toLong())
                ?: error("Unable to encode download request")
            try {
                CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                CFDictionarySetValue(query, kSecValueData, data)
                SecItemAdd(query, null) == errSecSuccess
            } finally {
                CFRelease(data)
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun remove(downloadId: String) {
        withKeychainQuery(downloadId) { query -> SecItemDelete(query) }
    }

    @OptIn(ExperimentalForeignApi::class)
    private inline fun <T> withKeychainQuery(
        downloadId: String,
        block: (CFMutableDictionaryRef) -> T,
    ): T {
        val service = CFStringCreateWithCString(null, keychainService, kCFStringEncodingUTF8)
            ?: error("Unable to encode download Keychain service")
        val account = CFStringCreateWithCString(null, downloadId, kCFStringEncodingUTF8)
            ?: error("Unable to encode download Keychain account")
        val query = CFDictionaryCreateMutable(
            allocator = null,
            capacity = 0L,
            keyCallBacks = null,
            valueCallBacks = null,
        ) ?: error("Unable to create download Keychain query")
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, service)
            CFDictionarySetValue(query, kSecAttrAccount, account)
            return block(query)
        } finally {
            CFRelease(query)
            CFRelease(account)
            CFRelease(service)
        }
    }
}
