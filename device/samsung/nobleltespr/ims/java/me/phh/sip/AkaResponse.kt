package me.phh.sip

data class SipAkaResult(
    val res: ByteArray,
    val ck: ByteArray,
    val ik: ByteArray,
)

fun decodeAkaResponse(encoded: String): SipAkaResult {
    val text = encoded.trim()
    val isHex = text.length in 4..512 && text.length % 2 == 0 &&
        (text.startsWith("DB", true) || text.startsWith("DC", true)) &&
        text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    return parseAkaResponse(if (isHex) text.toByteArray(Charsets.US_ASCII)
        else java.util.Base64.getDecoder().decode(text))
}

fun parseAkaResponse(raw: ByteArray): SipAkaResult {
    val text = raw.toString(Charsets.US_ASCII)
    val response = if (raw.size in 4..512 && raw.size % 2 == 0 &&
        (text.startsWith("DB", true) || text.startsWith("DC", true)) &&
        text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
        text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    } else raw
    require(response.isNotEmpty()) { "Empty SIM authentication response" }
    require(response[0] == 0xdb.toByte()) {
        if (response[0] == 0xdc.toByte()) "AKA synchronization failure (AUTS)"
        else "SIM authentication failed"
    }
    var position = 1
    fun field(): ByteArray {
        require(position < response.size) { "Missing AKA field length" }
        val length = response[position++].toInt() and 0xff
        require(length > 0 && length <= response.size - position) { "Truncated AKA field" }
        return response.copyOfRange(position, position + length).also { position += length }
    }
    val res = field()
    val ck = field()
    val ik = field()
    require(res.size in 4..16 && ck.size == 16 && ik.size == 16) { "Invalid AKA field lengths" }
    return SipAkaResult(res, ck, ik)
}
