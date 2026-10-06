package me.phh.sip

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

fun InputStream.sipReader(): SipReader = SipReader(this)

class SipReader(private val input: InputStream) {
    private val buffer = ByteArray(8192)
    private var position = 0
    private var limit = 0

    private fun refill(): Boolean {
        if (position < limit) return true
        limit = input.read(buffer)
        position = 0
        if (limit == 0) throw java.io.IOException("SIP input returned a zero-length read")
        return limit > 0
    }

    private fun read(): Int {
        if (!refill()) return -1
        return buffer[position++].toInt() and 0xff
    }
    fun readLine(): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val value = read()
            if (value < 0) {
                if (line.size() == 0) return null
                throw EOFException("Truncated SIP line")
            }
            if (value == 10) {
                val bytes = line.toByteArray()
                val size = if (bytes.lastOrNull() == 13.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, size, Charsets.US_ASCII)
            }
            require(line.size() < 65536) { "SIP line too long" }
            line.write(value)
        }
    }

    fun readNBytes2(len: Int): ByteArray {
        require(len in 0..1048576) { "Invalid SIP body length" }
        val bytes = ByteArray(len)
        var offset = 0
        while (offset < len) {
            if (!refill()) throw EOFException("Truncated SIP body: received $offset of $len bytes")
            val count = minOf(len - offset, limit - position)
            buffer.copyInto(bytes, offset, position, position + count)
            position += count
            offset += count
        }
        return bytes
    }
}
