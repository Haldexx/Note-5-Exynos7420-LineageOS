package me.phh.sip

import java.util.concurrent.atomic.AtomicInteger

enum class SipMethod {
    REGISTER,
    SUBSCRIBE,
    INVITE,
    PRACK,
    ACK,
    CANCEL,
    BYE,
    OPTIONS,
    MESSAGE,
    UPDATE,
    NOTIFY,
}

typealias SipStatusCode = Int

typealias SipHeader = String

typealias SipHeadersMap = Map<String, List<SipHeader>>

fun String.toSdpBody(): ByteArray =
    trim()
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split('\n')
        .joinToString("\r\n", postfix = "\r\n")
        .toByteArray(Charsets.US_ASCII)

fun List<String>.toSdpBody(): ByteArray =
    joinToString("\r\n", postfix = "\r\n").toByteArray(Charsets.US_ASCII)

@OptIn(ExperimentalStdlibApi::class)
abstract class SipMessage {
    abstract val firstLine: String
    abstract val headers: SipHeadersMap
    abstract val body: ByteArray

    fun toByteArray(): ByteArray =
        this.headers
            .asSequence()
            .map {
                (header, values) ->
                when (header) {
                    "allow",
                    "security-client",
                    "security-server",
                    "supported",
                    -> header to listOf(values.joinToString(", "))

                    else -> header to values
                }
            }.sortedBy {
                when (it.first) {
                    "via" -> 0
                    "p-preferred-identity" -> 1
                    "from" -> 2
                    "to" -> 3
                    "event" -> 4
                    "expires" -> 5
                    "contact" -> 6
                    "max-forwards" -> 7
                    "user-agent" -> 8
                    "route" -> 9
                    "call-id" -> 10
                    "require" -> 11
                    "proxy-require" -> 12
                    "cseq" -> 13
                    "p-access-network-info" -> 14
                    "content-length" -> 15
                    "security-verify" -> 16
                    else -> if (it.first.hashCode() > 0) it.first.hashCode() + 100 else 100 - it.first.hashCode()
                }
            }.fold(
                emptyList<String>(),
                { lines, (header, values) ->
                    lines +
                        values.map {
                            val newHeader = header.split("-").map { it.replaceFirstChar(Char::titlecase) }.joinToString("-")
                            "$newHeader: $it"
                        }
                },
            ).map { it.toByteArray() }
            .plus(listOf(ByteArray(0), this.body))
            .fold(this.firstLine.toByteArray(), { msg, line -> msg + "\r\n".toByteArray() + line })
}

open class SipCommonMessage(
    override val firstLine: String,
    private val headersParam: SipHeadersMap,
    override val body: ByteArray = ByteArray(0),
    private val autofill: Boolean = true,
) : SipMessage() {
    override val headers: SipHeadersMap = if (autofill) completeHeaders() else headersParam

    override fun toString(): String = String(toByteArray(), Charsets.US_ASCII).replace("\r\n", "\n> ")

    private fun completeHeaders(): SipHeadersMap {
        val newHeaders = mutableMapOf<String, List<SipHeader>>()
        if (headersParam["content-length"] == null) {
            newHeaders["content-length"] = listOf((this.body.size).toString())
        }
        if (headersParam["call-id"] == null) {
            newHeaders["call-id"] = listOf(randomBytes(12).toHex())
        }
        if (headersParam["max-forwards"] == null) {
            newHeaders["max-forwards"] = listOf("70")
        }
        if (headersParam["user-agent"] == null) {
            newHeaders["user-agent"] = listOf(userAgent)
        }
        val via = headersParam["via"]
        if (via != null) {
            newHeaders["via"] =
                via.map {
                    if (it.contains(";branch=")) {
                        it
                    } else {
                        "$it;branch=z9hG4bK${randomBytes(6).toHex()}"
                    }
                }
        }
        return headersParam + newHeaders
    }
}

val cseqCounter = AtomicInteger(2)

private const val DEFAULT_USER_AGENT = "SM-N920P-N920PVPS3DRH1 Samsung IMS 6.0"

val userAgent: String by lazy {
    try {
        val value = Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
            .invoke(null, "debug.phh.ims.user_agent", DEFAULT_USER_AGENT) as String
        value.ifBlank { DEFAULT_USER_AGENT }
    } catch (e: Throwable) {
        DEFAULT_USER_AGENT
    }
}

data class SipRequest(
    val method: SipMethod,
    val destination: String,
    private val headersParam: SipHeadersMap,
    override val body: ByteArray = ByteArray(0),
    private val autofill: Boolean = true,
) : SipMessage() {
    private val message: SipCommonMessage

    init {
        val headers = if (autofill) completeRequestHeaders() else headersParam

        message =
            SipCommonMessage(
                firstLine = "$method $destination SIP/2.0",
                headersParam = headers,
                body = body,
                autofill = autofill,
            )
    }

    override val firstLine = message.firstLine
    override val headers = message.headers

    override fun toString(): String = message.toString()

    private fun completeRequestHeaders(): SipHeadersMap {
        val newHeaders = mutableMapOf<String, List<SipHeader>>()

        if (headersParam["cseq"] == null) {
            val v = cseqCounter.getAndIncrement()
            newHeaders["cseq"] = listOf("$v ${this.method}")
        }

        val from = headersParam["from"]
        if (from != null) {
            newHeaders["from"] =
                from.map {
                    if (it.contains(";tag=")) {
                        it
                    } else {
                        "$it;tag=${randomBytes(6).toHex()}"
                    }
                }
        }

        return headersParam + newHeaders
    }
}

data class SipResponse(
    val statusCode: SipStatusCode,
    val statusString: String,
    private val headersParam: SipHeadersMap,
    override val body: ByteArray = ByteArray(0),
    private val autofill: Boolean = true,
) : SipMessage() {
    private val message: SipCommonMessage

    init {
        val headers = if (autofill) completeResponseHeaders() else headersParam

        message =
            SipCommonMessage(
                firstLine = "SIP/2.0 $statusCode $statusString",
                headersParam = headers,
                body = body,
                autofill = autofill,
            )
    }

    override val firstLine = message.firstLine
    override val headers = message.headers

    override fun toString(): String = message.toString()

    private fun completeResponseHeaders(): SipHeadersMap {
        val newHeaders = mutableMapOf<String, List<SipHeader>>()

        val to = headersParam["to"]
        if (to != null) {
            newHeaders["to"] =
                to.map {
                    if (it.contains(";tag=")) {
                        it
                    } else {
                        "$it;tag=${randomBytes(6).toHex()}"
                    }
                }
        }

        return headersParam + newHeaders
    }
}

private val splitHeader = "^\\s*([^:]+)\\s*:\\s*(.+)$".toRegex()
private val splitComma = "(<[^>]*>|[^,]+?)+".toRegex()

@OptIn(ExperimentalStdlibApi::class)
fun sipHeaderOf(line: String): Pair<String, List<SipHeader>>? {
    val (headerRaw, valueRaw) = splitHeader.find(line)?.destructured ?: return null
    val header =
        when (val headerLowCase = headerRaw.trim().lowercase()) {
            "i" -> "call-id"

            "m" -> "contact"

            "e" -> "content-encoding"

            "l" -> "content-length"

            "c" -> "content-type"

            "f" -> "from"

            "s" -> "subject"

            "k" -> "supported"

            "t" -> "to"

            "v" -> "via"

            else -> headerLowCase
        }
    val values =
        when (header) {
            "allow",
            "contact",
            "route",
            "record-route",
            "from",
            "p-asserted-identity",
            "security-client",
            "security-verify",
            "supported",
            "to",
            -> splitComma.findAll(valueRaw).toList().map { it.groupValues[0].trim() }

            else -> listOf(valueRaw)
        }

    return header to values
}

@OptIn(ExperimentalStdlibApi::class)
private fun splitParams(
    value: String,
    splitRegex: Regex,
    paramRegex: Regex,
    lowercase: Boolean,
): Pair<String, Map<String, String?>> {
    val paramSplit = splitRegex.findAll(value).toList().map { it.groupValues[0].trim() }
    return paramSplit[0] to
        paramSplit
            .slice(1..paramSplit.size - 1)
            .map Map@{
                val (a, b) = paramRegex.find(it)?.destructured ?: return@Map it.lowercase() to null
                a.lowercase() to (if (lowercase) b.lowercase() else b)
            }.toMap()
}

private val splitParam = "(<[^>]*>|[^;]+?)+".toRegex()
private val splitParamValue = "^([^=]+)=?(.*)".toRegex()

fun SipHeader.getParams(): Pair<String, Map<String, String?>> = splitParams(this, splitParam, splitParamValue, true)

private val splitAuth = """("[^"]*"|[^ ,]+?)+""".toRegex()
private val splitAuthValue = """^([^=]+)="?([^"]*)"?""".toRegex()

fun SipHeader.getAuthValues(): Pair<String, Map<String, String?>> = splitParams(this, splitAuth, splitAuthValue, false)

fun parseHeaders(sequence: Sequence<String>): SipHeadersMap =
    sequence.fold(
        emptyMap<String, List<SipHeader>>(),
        fold@{ headers, line ->
            val (header, value) = sipHeaderOf(line) ?: return@fold headers
            val oldVal = headers.get(header) ?: emptyList<SipHeader>()

            headers + (header to oldVal + value)
        },
    )

fun String.toSipHeadersMap(): SipHeadersMap = parseHeaders(this.lines().asSequence())

fun SipReader.parseHeaders(): SipHeadersMap {
    val lines = mutableListOf<String>()
    var bytes = 0
    while (true) {
        val line = readLine() ?: throw java.io.EOFException("Truncated SIP headers")
        if (line.isEmpty()) break
        bytes += line.length
        require(bytes <= 65536 && lines.size < 256) { "SIP headers too large" }
        if (line.startsWith(' ') || line.startsWith('\t')) {
            require(lines.isNotEmpty()) { "Header continuation without a header" }
            lines[lines.lastIndex] += " " + line.trimStart()
        } else lines.add(line)
    }
    return parseHeaders(lines.asSequence())
}

fun SipReader.parseMessage(): SipMessage? {
    var firstLine = readLine() ?: return null
    while (firstLine.isEmpty()) firstLine = readLine() ?: return null
    val headers = this.parseHeaders()
    val body =
        headers["content-length"]?.getOrNull(0)?.trim()?.toInt()?.let { this.readNBytes2(it) }
            ?: ByteArray(0)
    val firstLineSplit = firstLine.split(" ")
    when (firstLineSplit[0]) {
        "REGISTER",
        "SUBSCRIBE",
        "INVITE",
        "PRACK",
        "ACK",
        "CANCEL",
        "BYE",
        "OPTIONS",
        "MESSAGE",
        "UPDATE",
        "NOTIFY",
        -> {
            return SipRequest(
                method = SipMethod.valueOf(firstLineSplit[0]),
                destination = firstLineSplit[1],
                headersParam = headers,
                body = body,
                autofill = false,
            )
        }

        "SIP/2.0" -> {
            val code = firstLineSplit.getOrNull(1)?.toInt() ?: return null
            return SipResponse(
                statusCode = code,
                statusString = firstLineSplit.slice(2..firstLineSplit.size - 1).joinToString(" "),
                headersParam = headers,
                body = body,
                autofill = false,
            )
        }

        else -> {
            return SipCommonMessage(
                firstLine = firstLine,
                headersParam = headers,
                body = body,
                autofill = false,
            )
        }
    }
}

fun SipHeadersMap.uacDialogRoute(): List<String> = get("record-route").orEmpty().reversed()
