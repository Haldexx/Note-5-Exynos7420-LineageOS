package me.phh.sip

import java.util.concurrent.atomic.AtomicLong

class SdpOrigin(val sessionId: Long) {
    var version: Long = sessionId
        private set

    fun line(
        ipType: String,
        address: String,
    ): String = "o=- $sessionId $version IN $ipType $address"

    fun revise() {
        version++
    }

    companion object {
        const val NTP_EPOCH_OFFSET = 2208988800L

        private val lastSessionId = AtomicLong(0)

        fun next(nowMillis: Long = System.currentTimeMillis()): SdpOrigin {
            val ntp = nowMillis / 1000 + NTP_EPOCH_OFFSET
            return SdpOrigin(lastSessionId.updateAndGet { prev -> if (ntp > prev) ntp else prev + 1 })
        }
    }
}
