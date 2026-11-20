package com.hcode.ipswtoolkit

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

object LegacyDmg {
    fun keyFromHex(input: String): ByteArray {
        val hex = input.trim().filterNot { it.isWhitespace() }
        require(hex.length == 72 && hex.all { it in "0123456789abcdefABCDEF" }) {
            "A 72-character hexadecimal VFDecrypt key is required"
        }
        return ByteArray(36) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
    private fun readExact(input: InputStream, b: ByteArray) {
        var pos = 0
        while (pos < b.size) {
            val n = input.read(b, pos, b.size - pos)
            if (n < 0) error("Truncated encrypted image")
            if (n > 0) pos += n
        }
    }
    private fun skipExact(input: InputStream, count: Long) {
        var remaining = count
        val scratch = ByteArray(8192)
        while (remaining > 0) {
            val n = input.read(scratch, 0, min(remaining, scratch.size.toLong()).toInt())
            if (n < 0) error("Truncated image header")
            if (n > 0) remaining -= n
        }
    }
    fun decrypt(input: InputStream, output: OutputStream, hex: String, progress: (Int) -> Unit) {
        val key = keyFromHex(hex)
        try {
            val hdr = ByteArray(72)
            readExact(input, hdr)
            require(String(hdr, 0, 8, Charsets.US_ASCII) == "encrcdsa") {
                "Not a legacy encrcdsa filesystem image"
            }
            val b = ByteBuffer.wrap(hdr).order(ByteOrder.BIG_ENDIAN)
            require(b.getInt(8) == 2) { "Only encrcdsa v2 is supported" }
            val blockSize = b.getInt(52)
            val total = b.getLong(56)
            val offset = b.getLong(64)
            require(blockSize in 16..1048576 && blockSize % 16 == 0 && total > 0 &&
                    offset >= 72 && offset < 16777216) { "Invalid encrypted image header" }
            skipExact(input, offset - 72L)
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(key.copyOfRange(16, 36), "HmacSHA1"))
            val aes = SecretKeySpec(key.copyOfRange(0, 16), "AES")
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            val encrypted = ByteArray(blockSize)
            var written = 0L
            var index = 0L
            while (written < total) {
                require(index <= 0xffffffffL) { "Too many encrypted blocks" }
                readExact(input, encrypted)
                val number = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                    .putInt(index.toInt()).array()
                val iv = mac.doFinal(number).copyOfRange(0, 16)
                cipher.init(Cipher.DECRYPT_MODE, aes, IvParameterSpec(iv))
                val decrypted = cipher.doFinal(encrypted)
                val n = min(total - written, blockSize.toLong()).toInt()
                output.write(decrypted, 0, n)
                written += n
                index++
                if (index % 256L == 0L || written == total) progress((written * 100 / total).toInt())
            }
            output.flush()
        } finally { key.fill(0) }
    }
}
