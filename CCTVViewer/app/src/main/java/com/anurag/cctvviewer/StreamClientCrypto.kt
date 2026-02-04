package com.anurag.cctvviewer

import android.util.Log
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Used by StreamClient handshake/auth (CHAP style).
 * Kept top-level to reduce StreamClient.kt size.
 */
internal fun hmacSha256(key: String, salt: String): String {
    return try {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        val bytes = mac.doFinal(salt.toByteArray(Charsets.UTF_8))
        bytes.joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        Log.w("CCTV_CLIENT_CRYPTO", "HMAC error", e)
        ""
    }
}

