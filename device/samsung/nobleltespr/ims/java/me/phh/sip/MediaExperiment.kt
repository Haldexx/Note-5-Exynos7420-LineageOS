package me.phh.sip

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException

class MediaPortPair(val rtp: DatagramSocket, val rtcp: DatagramSocket?) : AutoCloseable {
    override fun close() {
        rtp.close()
        rtcp?.close()
    }

    companion object {
        fun open(address: InetAddress, paired: Boolean): MediaPortPair {
            if (!paired) return MediaPortPair(DatagramSocket(0, address), null)
            repeat(128) {
                val rtp = DatagramSocket(0, address)
                if (rtp.localPort % 2 != 0 || rtp.localPort >= 65535) {
                    rtp.close()
                } else {
                    try {
                        return MediaPortPair(rtp, DatagramSocket(rtp.localPort + 1, address))
                    } catch (_: SocketException) {
                        rtp.close()
                    }
                }
            }
            throw SocketException("Could not reserve an RTP/RTCP port pair")
        }
    }
}

fun rewriteMediaSdp(body: ByteArray, mode: String): ByteArray {
    if (mode != "media-bandwidth" && mode != "maxptime40") return body
    var inMedia = false
    val lines = body.toString(Charsets.US_ASCII).trimEnd('\r', '\n').split("\r\n")
    return lines.mapNotNull { line ->
        if (line.startsWith("m=")) inMedia = true
        when {
            mode == "media-bandwidth" && !inMedia &&
                (line.startsWith("b=AS:") || line.startsWith("b=RS:") || line.startsWith("b=RR:")) -> null
            mode == "maxptime40" && line == "a=maxptime:240" -> "a=maxptime:40"
            else -> line
        }
    }.joinToString("\r\n", postfix = "\r\n").toByteArray(Charsets.US_ASCII)
}
