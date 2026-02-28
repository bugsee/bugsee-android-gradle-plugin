package com.bugsee.android.gradle.util

import java.io.File
import java.security.MessageDigest

internal object HashUtils {

    fun sha1Hex(text: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(text.toByteArray(Charsets.UTF_8))
        return digestToHex(md.digest())
    }

    fun sha1Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                md.update(buffer, 0, read)
            }
        }
        return digestToHex(md.digest())
    }

    private fun digestToHex(digest: ByteArray): String =
        digest.joinToString("") { "%02x".format(it) }
}
