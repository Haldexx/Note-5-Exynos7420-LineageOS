package me.phh.sip

import java.io.OutputStream

/** One supported incoming SIP dialog. Retries never allocate another Telecom call. */
class IncomingDialogState(val callId: String, val inviteCseq: Long, val remoteTag: String,
                          val initialHeaders: Map<String, List<String>> = emptyMap()) {
    enum class Phase { RINGING, ANSWERING, ANSWERED, ACKNOWLEDGED, ENDED }
    enum class InviteAction { NEW, REPLAY, IN_DIALOG, BUSY, NO_DIALOG }

    @Volatile var phase = Phase.RINGING
        private set
    @Volatile var localTag: String? = null
    @Volatile var replyWriter: OutputStream? = null
    @Volatile var lastResponse: ByteArray? = null
    private val responses = mutableMapOf<Long, ByteArray>()
    @Volatile var lastInviteCseq: Long = inviteCseq
    @Volatile var finalResponse: ByteArray? = null
    @Volatile var finalResponseCseq: Long? = null
    @Volatile var finalAcknowledged = false

    val live: Boolean get() = phase != Phase.ENDED
    val answered: Boolean get() = phase == Phase.ANSWERED || phase == Phase.ACKNOWLEDGED

    @Synchronized fun rememberResponse(cseq: Long, bytes: ByteArray) {
        lastResponse = bytes
        responses[cseq] = bytes
        lastInviteCseq = maxOf(lastInviteCseq, cseq)
        while (responses.size > 8) {
            responses.remove(responses.keys.filter { it != inviteCseq }.min())
        }
    }

    @Synchronized fun responseFor(cseq: Long): ByteArray? =
        responses[cseq]

    @Synchronized fun requestAnswer(): Boolean {
        if (phase != Phase.RINGING) return false
        phase = Phase.ANSWERING
        return true
    }

    @Synchronized fun answerSent(): Boolean {
        if (phase != Phase.ANSWERING) return false
        phase = Phase.ANSWERED
        return true
    }

    @Synchronized fun end(): Boolean {
        if (!live) return false
        phase = Phase.ENDED
        return true
    }

    fun matches(headers: Map<String, List<String>>, requireLocalTag: Boolean = true): Boolean =
        headers["call-id"]?.firstOrNull() == callId &&
            tag(headers["from"]?.firstOrNull()) == remoteTag &&
            (!requireLocalTag || (localTag != null && tag(headers["to"]?.firstOrNull()) == localTag))

    fun matchesCancel(headers: Map<String, List<String>>): Boolean =
        matches(headers, false) && cseq(headers) == inviteCseq &&
            branch(initialHeaders["via"]?.firstOrNull()) != null &&
            branch(initialHeaders["via"]?.firstOrNull()) == branch(headers["via"]?.firstOrNull())

    @Synchronized fun inviteAction(headers: Map<String, List<String>>): InviteAction {
        val seq = cseq(headers) ?: return InviteAction.NO_DIALOG
        if (matches(headers, false)) {
            if (seq == lastInviteCseq || seq == inviteCseq || responses.containsKey(seq)) return InviteAction.REPLAY
            if (live && matches(headers) && seq > lastInviteCseq) return InviteAction.IN_DIALOG
            return InviteAction.NO_DIALOG
        }
        return if (live) InviteAction.BUSY
        else if (tag(headers["to"]?.firstOrNull()) != null) InviteAction.NO_DIALOG
        else InviteAction.NEW
    }

    @Synchronized fun acknowledge(headers: Map<String, List<String>>): Boolean {
        if (!live || !matches(headers) || cseq(headers) != finalResponseCseq) return false
        finalAcknowledged = true
        if (phase == Phase.ANSWERED) phase = Phase.ACKNOWLEDGED
        return true
    }

    companion object {
        fun cseq(headers: Map<String, List<String>>): Long? =
            headers["cseq"]?.firstOrNull()?.trim()?.split(Regex("\\s+"))?.firstOrNull()
                ?.toLongOrNull()?.takeIf { it >= 0 && it < 2147483648L }

        fun tag(header: String?): String? = header?.let {
            Regex("(?:^|;)\\s*tag\\s*=\\s*([^;\\s>]+)", RegexOption.IGNORE_CASE)
                .find(it)?.groupValues?.get(1)
        }

        fun branch(header: String?): String? = header?.let {
            Regex("(?:^|;)\\s*branch\\s*=\\s*([^;\\s]+)", RegexOption.IGNORE_CASE)
                .find(it)?.groupValues?.get(1)
        }
    }
}
