package com.nuvio.app.features.watchtogether.hosted

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
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecRandomDefault
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

internal actual object WatchTogetherPlatformSecurity : WatchTogetherCredentialStore {
    private const val KEYCHAIN_SERVICE = "com.nuvio.media.watchtogether"

    @OptIn(ExperimentalForeignApi::class)
    actual fun secureRandomBytes(size: Int): ByteArray {
        require(size > 0)
        val bytes = ByteArray(size)
        val status = SecRandomCopyBytes(kSecRandomDefault, size.toULong(), bytes.refTo(0))
        check(status == errSecSuccess) { "Secure random generation failed" }
        return bytes
    }

    actual fun nowEpochMs(): Long = (NSDate().timeIntervalSince1970 * 1_000.0).toLong()

    @OptIn(ExperimentalForeignApi::class)
    override fun load(key: String): String? = withKeychainQuery(key) { query ->
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
    override fun save(key: String, value: String) {
        delete(key)
        withKeychainQuery(key) { query ->
            val bytes = value.encodeToByteArray().toUByteArray()
            val data = CFDataCreate(null, bytes.refTo(0), bytes.size.toLong())
                ?: error("Unable to encode Watch Together credential")
            try {
                CFDictionarySetValue(query, kSecValueData, data)
                check(SecItemAdd(query, null) == errSecSuccess) {
                    "Unable to store Watch Together credential"
                }
            } finally {
                CFRelease(data)
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun delete(key: String) {
        withKeychainQuery(key) { query -> SecItemDelete(query) }
    }

    @OptIn(ExperimentalForeignApi::class)
    private inline fun <T> withKeychainQuery(
        key: String,
        block: (CFMutableDictionaryRef) -> T,
    ): T {
        val service = CFStringCreateWithCString(null, KEYCHAIN_SERVICE, kCFStringEncodingUTF8)
            ?: error("Unable to encode Watch Together Keychain service")
        val account = CFStringCreateWithCString(null, key, kCFStringEncodingUTF8)
            ?: error("Unable to encode Watch Together Keychain account")
        val query = CFDictionaryCreateMutable(
            allocator = null,
            capacity = 0L,
            keyCallBacks = null,
            valueCallBacks = null,
        ) ?: error("Unable to create Watch Together Keychain query")
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
