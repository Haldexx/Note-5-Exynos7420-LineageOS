package me.phh.sip

import android.telephony.PhoneNumberUtils
import java.nio.ByteBuffer

enum class SmsType(val value: Byte) {
    RP_DATA_TO_NETWORK(0), RP_DATA_FROM_NETWORK(1),
    RP_ACK_TO_NETWORK(2), RP_ACK_FROM_NETWORK(3),
    RP_ERROR_TO_NETWORK(4), RP_ERROR_FROM_NETWORK(5), RP_SNMA_TO_NETWORK(6),
}

data class SipSms(val type: SmsType, val ref: Byte, val pdu: ByteArray?, val cause: Int? = null)

// Reject malformed network input without ending the SIP receive loop.
fun ByteArray.SipSmsDecode(): SipSms? {
    if (size < 2) return null
    val type = first()
    val ref = this[1]
    if (type == SmsType.RP_ACK_FROM_NETWORK.value)
        return SipSms(SmsType.RP_ACK_FROM_NETWORK, ref, null)
    if (type == SmsType.RP_ERROR_FROM_NETWORK.value) {
        if (size < 4 || (this[2].toInt() and 255) < 1 ||
            (this[2].toInt() and 255) > size - 3) return null
        return SipSms(SmsType.RP_ERROR_FROM_NETWORK, ref, null, this[3].toInt() and 127)
    }
    if (type != SmsType.RP_DATA_FROM_NETWORK.value) return null
    var offset = 2
    fun readField(): ByteArray? {
        if (offset >= size) return null
        val length = this[offset++].toInt() and 255
        if (length > size - offset) return null
        return copyOfRange(offset, offset + length).also { offset += length }
    }
    val origin = readField() ?: return null
    readField() ?: return null
    val tpdu = readField() ?: return null
    if (tpdu.isEmpty() || offset != size) return null
    return SipSms(SmsType.RP_DATA_FROM_NETWORK, ref,
        byteArrayOf(origin.size.toByte()) + origin + tpdu)
}

fun normalizeSmscAddress(value: String?): String? {
    val match = Regex("\"?(\\+?[0-9]+)\"?(?:,([0-9]+))?")
        .matchEntire(value?.trim() ?: return null) ?: return null
    val number = match.groupValues[1]
    val toa = match.groupValues[2].toIntOrNull()
    return if (number.startsWith('+') || toa?.let { it and 0x70 == 0x10 } == true)
        "+${number.removePrefix("+")}" else number
}

// ImsSmsDispatcher passes the hexadecimal SMSC field, including its length octet.
fun decodeImsSmsc(encoded: String?): String? {
    if (encoded.isNullOrEmpty() || encoded.length % 2 != 0 ||
        !encoded.matches(Regex("[0-9a-fA-F]+"))) return null
    val bytes = encoded.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val length = bytes[0].toInt() and 255
    if (length == 0 || length != bytes.size - 1) return null
    return runCatching {
        normalizeSmscAddress(PhoneNumberUtils.calledPartyBCDToString(bytes, 1, length,
            PhoneNumberUtils.BCD_EXTENDED_TYPE_CALLED_PARTY))
    }.getOrNull()
}

fun sipSmsServiceUri(identity: String?, smsc: String, realm: String): String {
    val uri = identity?.trim()?.takeIf { it.isNotEmpty() }
    if (uri != null) {
        require(uri.none { it.isWhitespace() || it in "<>\r\n" }) { "Invalid SMS service URI" }
        val scheme = uri.substringBefore(':').lowercase()
        require(scheme in setOf("sip", "sips", "tel") && uri.substringAfter(':').isNotEmpty() &&
            !uri.substringAfter(':').startsWith("sip:", true) &&
            !uri.substringAfter(':').startsWith("sips:", true) &&
            !uri.substringAfter(':').startsWith("tel:", true)) { "Invalid SMS service URI scheme" }
        return uri
    }
    require(realm.matches(Regex("[A-Za-z0-9.-]+"))) { "Invalid SMS realm" }
    val number = normalizeSmscAddress(smsc) ?: error("No SMS service-centre address")
    return "sip:$number@$realm;user=phone"
}

fun SipSmsEncodeSms(ref: Byte, smsc: String?, pdu: ByteArray): ByteArray {
    require(pdu.isNotEmpty() && pdu.size <= 255) { "Invalid SMS TPDU length" }
    val bcd = if (smsc == null) ByteArray(0) else
        PhoneNumberUtils.numberToCalledPartyBCD(smsc, PhoneNumberUtils.BCD_EXTENDED_TYPE_CALLED_PARTY)
            ?: error("Invalid SMS service-centre address")
    require(bcd.size <= 255)
    return ByteBuffer.allocate(5 + bcd.size + pdu.size)
        .put(SmsType.RP_DATA_TO_NETWORK.value).put(ref).put(0)
        .put(bcd.size.toByte()).put(bcd).put(pdu.size.toByte()).put(pdu).array()
}

fun SipSmsEncodeAck(ref: Byte): ByteArray = byteArrayOf(2, ref)

fun SipSmsEncodeError(ref: Byte, cause: Int = 111): ByteArray =
    byteArrayOf(4, ref, 1, (cause and 127).toByte())

// RP references are separate from Android's TP message reference. They must be
// unique among outstanding submits, including multipart messages with TP-MR 0.
class SmsSubmitTracker {
    data class Submission(val callId: String, val ref: Int, val success: () -> Unit, val failure: () -> Unit)
    private val pending = mutableMapOf<Int, Submission>()
    private val retired = mutableMapOf<Int, Long>()
    private var nextRef = 0

    @Synchronized
    fun start(callId: String, success: () -> Unit, failure: () -> Unit): Submission {
        val now = System.nanoTime()
        retired.entries.removeAll { it.value <= now }
        repeat(256) {
            val ref = nextRef
            nextRef = (nextRef + 1) and 255
            if (ref !in pending && ref !in retired) {
                return Submission(callId, ref, success, failure).also { pending[ref] = it }
            }
        }
        error("No available SMS relay reference")
    }

    @Synchronized
    fun find(ref: Int, inReplyTo: String?): Submission? = pending[ref]?.takeIf {
        inReplyTo == null || inReplyTo.trim() == it.callId
    }

    @Synchronized
    fun finish(submission: Submission): Boolean {
        if (pending[submission.ref] !== submission) return false
        pending.remove(submission.ref)
        retired[submission.ref] = System.nanoTime() + 64_000_000_000L
        return true
    }

    @Synchronized
    fun snapshot(): List<Submission> = pending.values.toList()
}
