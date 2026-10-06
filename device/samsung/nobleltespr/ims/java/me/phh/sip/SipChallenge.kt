package me.phh.sip

import android.telephony.Rlog
import android.telephony.TelephonyManager
import android.util.Base64

private const val TAG = "PHH SipChallenge"

fun sipAkaChallenge(
    tm: TelephonyManager,
    nonceB64: String,
): SipAkaResult {
    val nonce = Base64.decode(nonceB64, Base64.DEFAULT)

    require(nonce.size >= 32) { "Truncated AKA nonce" }
    val rand = nonce.take(16)
    val autn = nonce.drop(16).take(16)

    val challengeBytes = listOf(rand.size.toByte()) + rand + autn.size.toByte() + autn
    val challengeArray = challengeBytes.toByteArray()

    val challenge = Base64.encodeToString(challengeArray, Base64.NO_WRAP)

    val responseB64 =
        tm.getIccAuthentication(
            TelephonyManager.APPTYPE_USIM,
            TelephonyManager.AUTHTYPE_EAP_AKA,
            challenge,
        )
    requireNotNull(responseB64) { "SIM authentication returned no result" }
    return decodeAkaResponse(responseB64)
}

data class SipAkaDigestSess(
    val user: String,
    val realm: String,
    val uri: String,
    val nonceB64: String,
    val opaque: String?,
    private val akaResult: SipAkaResult,
) {
    var nonceCount: String = "0"
    var cnonce: String = ""
    private val H1 = ("$user:$realm:".toByteArray() + akaResult.res).toMD5()
    private val H2 = "REGISTER:$uri".toMD5()
    var digest: String = ""

    init {
        increment()
    }

    fun increment() {
        nonceCount = "%08d".format(nonceCount.toInt() + 1)
        cnonce = randomBytes(8).toHex() // 16 bytes on some traces
        digest = "$H1:$nonceB64:$nonceCount:$cnonce:auth:$H2".toMD5()
    }

    override fun toString(): String =
        """Digest username="$user",realm="$realm",nonce="$nonceB64",uri="$uri",response="$digest",algorithm=AKAv1-MD5,cnonce="$cnonce",qop=auth,nc=$nonceCount""" +
            (if (opaque != null) ",opaque=$opaque" else "")
}

data class SipAkaDigest(
    val user: String,
    val realm: String,
    val uri: String,
    val nonceB64: String,
    val opaque: String?,
    private val akaResult: SipAkaResult,
) {
    private val H1 = ("$user:$realm:".toByteArray() + akaResult.res).toMD5()
    private val H2 = "REGISTER:$uri".toMD5()
    var digest: String = ""

    init {
        increment()
    }

    fun increment() {
        digest = "$H1:$nonceB64:$H2".toMD5()
    }

    override fun toString(): String =
        """Digest username="$user",realm="$realm",nonce="$nonceB64",uri="$uri",response="$digest",algorithm=AKAv1-MD5""" +
            (if (opaque != null) ",opaque=$opaque" else "")
}
