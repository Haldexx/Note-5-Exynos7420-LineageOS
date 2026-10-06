// Maintained by Haldexx (https://github.com/Haldexx)

package me.phh.sip

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.net.*
import android.os.Handler
import android.os.HandlerThread
import android.telephony.CellInfoGsm
import android.telephony.CellIdentityLte
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.PhoneNumberUtils
import android.telephony.Rlog
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.ims.stub.ImsRegistrationImplBase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.*
import java.net.*
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

private data class smsHeaders(
    val dest: String,
    val callId: String,
    val cseq: String,
)

class SipHandler(
    val ctxt: Context,
) {
    companion object {
        private const val TAG = "PHH SipHandler"

        private const val AMR_NB_BIT_RATE = 12200

        private val AMR_NB_FRAME_BITS = intArrayOf(95, 103, 118, 134, 148, 159, 204, 244, 39)
        private const val AMR_NO_DATA = 15

        fun amrBandwidthEfficientToStorage(buf: ByteArray, off: Int, len: Int): List<ByteArray>? {
            val end = (off + len) * 8
            var pos = off * 8 + 4
            fun bit(): Int {
                val b = (buf[pos ushr 3].toInt() shr (7 - (pos and 7))) and 1
                pos++
                return b
            }
            val toc = ArrayList<Pair<Int, Int>>()
            do {
                if (pos + 6 > end) return null
                val more = bit()
                var ft = 0
                repeat(4) { ft = (ft shl 1) or bit() }
                val q = bit()
                if (ft in 9..14) return null
                toc.add(ft to q)
            } while (more == 1 && toc.size < 16)
            return toc.map { (ft, q) ->
                val n = if (ft <= 8) AMR_NB_FRAME_BITS[ft] else 0
                if (pos + n > end) return null
                val frame = ByteArray(1 + (n + 7) / 8)
                frame[0] = ((ft shl 3) or (q shl 2)).toByte()
                for (i in 0 until n) {
                    if (bit() == 1) frame[1 + i / 8] = (frame[1 + i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
                }
                frame
            }
        }

        private const val RECONNECT_ATTEMPTS = 6
        private const val RECONNECT_MAX_BACKOFF_MS = 30_000L
    }

    private val mediaPorts = java.util.concurrent.CopyOnWriteArrayList<MediaPortPair>()
    private fun mediaMode(): String = android.os.SystemProperties.get("debug.phh.ims.media", "baseline")
    private fun labSdp(body: ByteArray): ByteArray = rewriteMediaSdp(body, mediaMode())
    private fun openMediaSocket(): DatagramSocket {
        val mode = mediaMode()
        val ports = MediaPortPair.open(localAddr, mode == "paired")
        try {
            network.bindSocket(ports.rtp)
            ports.rtcp?.let { network.bindSocket(it) }
            ports.rtp.soTimeout = 500
            mediaPorts.add(ports)
            Rlog.i(TAG, "IMS-LAB media=$mode rtp=${ports.rtp.localPort} rtcp=${ports.rtcp?.localPort}")
            return ports.rtp
        } catch (e: Throwable) { ports.close(); throw e }
    }
    private fun closeMediaSockets() {
        mediaPorts.forEach { it.close() }
        mediaPorts.clear()
    }

    val myHandler = Handler(HandlerThread("PhhMmTelFeature").apply { start() }.looper)
    val myExecutor = Executor { p0 -> myHandler.post(p0) }

    private val subscriptionManager: SubscriptionManager
    private val telephonyManager: TelephonyManager
    private val connectivityManager: ConnectivityManager
    private val ipSecManager: IpSecManager

    init {
        subscriptionManager = ctxt.getSystemService(SubscriptionManager::class.java)
        telephonyManager = ctxt.getSystemService(TelephonyManager::class.java)
        connectivityManager = ctxt.getSystemService(ConnectivityManager::class.java)
        ipSecManager = ctxt.getSystemService(IpSecManager::class.java)
    }

    @SuppressLint("MissingPermission")
    private val activeSubscription = subscriptionManager.activeSubscriptionInfoList!![0]

    private val deviceImei =
        (
            try {
                telephonyManager.getImei(activeSubscription.simSlotIndex)
            } catch (e: Throwable) {
                Rlog.w(TAG, "getImei() failed, falling back to getDeviceId()", e)
                null
            }
        )?.takeIf { it.length >= 14 }
            ?: telephonyManager.getDeviceId(activeSubscription.simSlotIndex)

    private val imei = android.os.SystemProperties.get("debug.phh.ims.test_imei", "")
        .takeIf { ctxt.packageName == "me.phh.ims.lab" && it.matches(Regex("[0-9]{15}")) }
        ?.also { Rlog.w(TAG, "IMS-LAB temporary SIP identity override enabled; modem identity unchanged") }
        ?: deviceImei

    init {
        val meid =
            try {
                telephonyManager.getMeid(activeSubscription.simSlotIndex)
            } catch (e: Throwable) {
                null
            }
        Rlog.d(
            TAG,
            "device identity: using len=${imei.length} tac=${imei.take(8)}" +
                " (meid present=${meid != null} meidMatches=${meid == imei})",
        )
    }
    private val subId = activeSubscription.subscriptionId
    private val mcc = telephonyManager.simOperator.substring(0 until 3)
    private var mnc =
        telephonyManager.simOperator.substring(3).let { if (it.length == 2) "0$it" else it }
    private val imsi = telephonyManager.subscriberId

    val isControlSocketUdp =
        when (mcc + mnc) {
            "450006" -> true

            "208010" -> true

            else -> false
        }
    val forceSmsc =
        when (mcc + mnc) {
            "450006" -> "821080010585"

            else -> null
        }

    val requireNonsessAka =
        when (mcc + mnc) {
            "450006" -> true
            else -> false
        }

    private val realm = "ims.mnc$mnc.mcc$mcc.3gppnetwork.org"
    private val user = "$imsi@$realm"
    private var akaDigest =
        """Digest username="$user",realm="$realm",nonce="",uri="sip:$realm",response="",algorithm=AKAv1-MD5"""

    fun generateCallId(): SipHeadersMap {
        val callId = randomBytes(12).toHex()
        return mapOf("call-id" to listOf(callId))
    }

    private var registerCounter = 1
    private var registerHeaders =
        """
        From: <sip:$user>
        To: <sip:$user>
        """.toSipHeadersMap() + generateCallId()
    private var commonHeaders = "".toSipHeadersMap()
    private var contact = ""
    private var mySip = ""
    private var myTel = ""

    private lateinit var localAddr: InetAddress
    private lateinit var pcscfAddr: InetAddress

    data class SipIpsecSettings(
        val clientSpiC: IpSecManager.SecurityParameterIndex,
        val clientSpiS: IpSecManager.SecurityParameterIndex,
        val serverSpiC: IpSecManager.SecurityParameterIndex? = null,
        val serverSpiS: IpSecManager.SecurityParameterIndex? = null,
    )

    lateinit var ipsecSettings: SipIpsecSettings

    private lateinit var network: Network

    private lateinit var plainSocket: SipConnection
    private lateinit var socket: SipConnection
    private lateinit var serverSocket: SipConnectionTcpServer
    private lateinit var serverSocketUdp: SipConnectionUdpServer
    private var reliableSequenceCounter = 67

    private val cbLock = ReentrantLock()
    private var requestCallbacks: Map<SipMethod, ((SipRequest) -> Int)> = mapOf()
    private var responseCallbacks: Map<String, ((SipResponse) -> Boolean)> = mapOf()
    private var imsReady = false
    var imsReadyCallback: (() -> Unit)? = null
    var imsRegisteringCallback: (() -> Unit)? = null
    @Volatile var registrationTech = ImsRegistrationImplBase.REGISTRATION_TECH_LTE
        private set
    var imsFailureCallback: (() -> Unit)? = null
    var onSmsReceived: ((Int, String, ByteArray) -> Unit)? = null
    var onSmsStatusReportReceived: ((Int, String, ByteArray) -> Unit)? = null
    var onIncomingCall: ((handle: Object, from: String, extras: Map<String, String>) -> Unit)? =
        null
    var onOutgoingCallConnected: ((handle: Object, extras: Map<String, String>) -> Unit)? =
        null
    var onOutgoingCallFailed: ((Int, String) -> Unit)? = null
    var onOutgoingCallProgressing: ((earlyMedia: Boolean) -> Unit)? = null
    var onCancelledCall: ((handle: Object, from: String, extras: Map<String, String>) -> Unit)? =
        null
    private val smsLock = ReentrantLock()
    private var smsToken = 0
    private val smsHeadersMap = mutableMapOf<Int, smsHeaders>()

    fun setRequestCallback(
        method: SipMethod,
        cb: (SipRequest) -> Int,
    ) {
        cbLock.withLock { requestCallbacks += (method to cb) }
    }

    fun setResponseCallback(
        callId: String,
        cb: (SipResponse) -> Boolean,
    ) {
        cbLock.withLock { responseCallbacks += (callId to cb) }
    }

    fun parseMessage(
        reader: SipReader,
        writer: OutputStream,
    ): Boolean {
        val msg =
            try {
                reader.parseMessage()
            } catch (e: SocketException) {
                Rlog.d(TAG, "Got exception $e")
                if ("$e" == "java.net.SocketException: Try again") {
                    return true
                }
                throw e
            }
        Rlog.d(TAG, "RObject() message $msg")
        if (msg is SipResponse) {
            return handleResponse(msg)
        }
        if (msg !is SipRequest) {
            Rlog.d(TAG, "Got invalid message! Closing socket (except main)")
            return false
        }

        val requestCb = cbLock.withLock { requestCallbacks[msg.method] }
        var status = 200
        if (requestCb != null) {
            status = requestCb(msg)
        }
        if (status == 0) return true
        val reply =
            SipResponse(
                statusCode = status,
                statusString =
                    if (status == 200) {
                        "OK"
                    } else if (status == 100) {
                        "Trying"
                    } else {
                        "ERROR"
                    },
                headersParam =
                    msg.headers.filter { (k, _) ->
                        k in listOf("cseq", "via", "from", "to", "call-id")
                    },
            )
        Rlog.d(TAG, "Replying back with $reply")
        synchronized(writer) { writer.write(reply.toByteArray()) }

        return true
    }

    fun handleResponse(response: SipResponse): Boolean {
        val callId = response.headers["call-id"]?.get(0)
        if (callId == null) {
            return false
        }
        val responseCb = cbLock.withLock { responseCallbacks[callId] }
        if (responseCb == null) {
            return true
        }

        if (responseCb(response)) {
            cbLock.withLock { responseCallbacks -= callId }
        }
        return true
    }

    var abandonnedBecauseOfNoPcscf = false

    private suspend fun reconnectWithRetry() {
        var backoffMs = 2_000L
        var generation = registrationGeneration.get()
        for (attempt in 1..RECONNECT_ATTEMPTS) {
            try {
                if (!connectUnlessSuperseded(generation, "reconnect attempt $attempt")) return
                generation = registrationGeneration.get()
                if (!abandonnedBecauseOfNoPcscf) {
                    Rlog.d(TAG, "Reconnected on attempt $attempt")
                    return
                }
                Rlog.w(TAG, "Reconnect attempt $attempt: no P-CSCF on the IMS bearer yet")
            } catch (t: Throwable) {
                Rlog.w(TAG, "Reconnect attempt $attempt failed", t)
                generation = registrationGeneration.get()
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(RECONNECT_MAX_BACKOFF_MS)
        }
        Rlog.e(TAG, "Giving up reconnecting after $RECONNECT_ATTEMPTS attempts")
        imsFailureCallback?.invoke()
    }

    private val registrationGeneration = AtomicInteger(0)

    @Synchronized
    fun connectUnlessSuperseded(generationAtTrigger: Int, why: String): Boolean {
        if (registrationGeneration.get() != generationAtTrigger && !abandonnedBecauseOfNoPcscf) {
            Rlog.d(TAG, "$why: the stack reconnected since; not connecting again")
            return false
        }
        connect()
        return true
    }

    private fun closeSignalling() {
        if (this::plainSocket.isInitialized) runCatching { plainSocket.close() }
        if (this::socket.isInitialized) runCatching { socket.close() }
        if (this::serverSocket.isInitialized) runCatching { serverSocket.close() }
        if (this::serverSocketUdp.isInitialized) runCatching { serverSocketUdp.socket.close() }
        if (this::ipsecSettings.isInitialized) {
            runCatching { ipsecSettings.clientSpiC.close() }
            runCatching { ipsecSettings.clientSpiS.close() }
            runCatching { ipsecSettings.serverSpiC?.close() }
            runCatching { ipsecSettings.serverSpiS?.close() }
        }
    }

    fun refreshRegistrationTech(): Int {
        if (!this::network.isInitialized) return registrationTech
        @Suppress("DEPRECATION")
        val subtype = connectivityManager.getNetworkInfo(network)?.subtype
            ?: TelephonyManager.NETWORK_TYPE_UNKNOWN
        val iface = connectivityManager.getLinkProperties(network)?.interfaceName
        val tech = me.phh.ims.PhhMmTelFeatureProtected.bearerRegistrationTech(
            android.os.Build.DEVICE == "nobleltedv", iface, subtype)
        if (tech != registrationTech) {
            registrationTech = tech
            if (imsReady) imsReadyCallback?.invoke()
        }
        return tech
    }

    @Synchronized
    fun connect() {
        registrationGeneration.incrementAndGet()
        closeSignalling()
        imsReady = false
        refreshRegistrationTech()
        imsRegisteringCallback?.invoke()
        val lp = connectivityManager.getLinkProperties(network)
        val count = if (lp == null) 1 else
            (lp.javaClass.getMethod("getPcscfServers").invoke(lp) as List<*>).size.coerceAtLeast(1)
        val override = android.os.SystemProperties.getInt("debug.phh.ims.pcscf", -1)
        val choices = if (override >= 0) listOf(override.coerceAtMost(count - 1)) else (0 until count).toList()
        var lastFailure: IOException? = null
        for (index in choices) {
            try {
                commonHeaders = emptyMap()
                registerHeaders = "From: <sip:$user>\nTo: <sip:$user>".toSipHeadersMap() + generateCallId()
                akaDigest = """Digest username="$user",realm="$realm",nonce="",uri="sip:$realm",response="",algorithm=AKAv1-MD5"""
                connectAttempt(index)
                return
            } catch (e: IOException) {
                Rlog.w(TAG, "IMS proxy index=$index transport/registration failed; trying next candidate", e)
                lastFailure = e
                closeSignalling()
            } catch (e: Throwable) {
                closeSignalling()
                throw e
            }
        }
        imsFailureCallback?.invoke()
        throw lastFailure ?: IOException("No IMS proxy could be contacted")
    }

    private fun connectAttempt(proxyIndex: Int) {
        abandonnedBecauseOfNoPcscf = false
        Rlog.d(TAG, "Trying to connect to SIP server")
        val lp = connectivityManager.getLinkProperties(network)
        if (lp == null) {
            abandonnedBecauseOfNoPcscf = true
            return
        }
        Rlog.d(TAG, "Got link properties $lp")
        val pcscfs = (lp!!.javaClass.getMethod("getPcscfServers").invoke(lp) as List<*>).sortedBy { if (it is Inet6Address) 0 else 1 }
        val pcscf =
            if (pcscfs.isNotEmpty()) {
                val index = proxyIndex.coerceIn(0, pcscfs.lastIndex)
                Rlog.i(TAG, "IMS-LAB selecting carrier P-CSCF index=$index count=${pcscfs.size}")
                pcscfs[index] as InetAddress
            } else {
                val dnsFallback = android.os.SystemProperties
                    .get("persist.ims.pcscf_fallback", "")
                    .takeIf { it.isNotEmpty() }
                    ?.let { try { network.getByName(it) } catch (e: Exception) { null } }
                if (dnsFallback != null) {
                    Rlog.w(TAG, "No P-CSCF from RIL, using fallback: $dnsFallback")
                    dnsFallback
                } else {
                    Rlog.w(TAG, "No P-CSCF and all fallbacks failed, waiting for onLinkPropertiesChanged")
                    abandonnedBecauseOfNoPcscf = true
                    return
                }
            }

        localAddr =
            lp.linkAddresses
                .map { it.address }
                .sortedBy { if (it is Inet6Address) 0 else 1 }
                .first()
        pcscfAddr = pcscf

        Rlog.w(TAG, "Connecting with address $localAddr to $pcscfAddr")

        val clientSpiC = ipSecManager.allocateSecurityParameterIndex(localAddr)
        val clientSpiS = ipSecManager.allocateSecurityParameterIndex(localAddr, clientSpiC.spi + 1)
        ipsecSettings =
            SipIpsecSettings(
                clientSpiS = clientSpiS,
                clientSpiC = clientSpiC,
            )

        plainSocket =
            if (isControlSocketUdp) {
                SipConnectionUdp(network, pcscfAddr, localAddr)
            } else {
                SipConnectionTcp(network, pcscfAddr, localAddr)
            }
        if (plainSocket is SipConnectionTcp) (plainSocket as SipConnectionTcp).socket.soTimeout = 15000
        plainSocket.connect(5060)
        socket =
            if (plainSocket is SipConnectionTcp) {
                SipConnectionTcp(network, pcscfAddr, plainSocket.gLocalAddr())
            } else {
                SipConnectionUdp(network, pcscfAddr, plainSocket.gLocalAddr())
            }
        serverSocket =
            SipConnectionTcpServer(network, pcscfAddr, plainSocket.gLocalAddr(), socket.gLocalPort() + 1)
        serverSocketUdp =
            SipConnectionUdpServer(network, pcscfAddr, plainSocket.gLocalAddr(), socket.gLocalPort() + 1)

        Rlog.d(
            TAG,
            "Src port is ${socket.gLocalPort()}, TCP server port is ${serverSocket.localPort}, UDP server port is ${serverSocketUdp.localPort}",
        )
        updateCommonHeaders(plainSocket)
        register(plainSocket.gWriter())
        val plainRegReply =
            if (plainSocket is SipConnectionTcp) {
                plainSocket.gReader().parseMessage()
            } else {
                if (select(listOf(serverSocketUdp.getChannel(), plainSocket.getChannel())) == 0) {
                    serverSocketUdp.gReader().parseMessage()
                } else {
                    plainSocket.gReader().parseMessage()
                }
            }
        Rlog.d(TAG, "Received $plainRegReply")
        plainSocket.close()
        if (plainRegReply !is SipResponse || plainRegReply.statusCode != 401) {
            Rlog.w(TAG, "Didn't get expected response from initial register, aborting")
            throw IOException("Initial REGISTER got $plainRegReply instead of 401")
        }

        val (wwwAuthenticateType, wwwAuthenticateParams) =
            plainRegReply.headers["www-authenticate"]!![0].getAuthValues()
        require(wwwAuthenticateType == "Digest")
        val nonceB64 = wwwAuthenticateParams["nonce"]!!
        val challengeRealm = wwwAuthenticateParams["realm"] ?: realm

        Rlog.d(TAG, "Requesting AKA challenge")
        val akaResult = sipAkaChallenge(telephonyManager, nonceB64)
        akaDigest =
            if (requireNonsessAka || wwwAuthenticateParams["qop"] == null) {
                SipAkaDigest(
                    user = user,
                    realm = challengeRealm,
                    uri = "sip:$realm",
                    nonceB64 = nonceB64,
                    opaque = wwwAuthenticateParams["opaque"],
                    akaResult = akaResult,
                ).toString()
            } else {
                SipAkaDigestSess(
                    user = user,
                    realm = challengeRealm,
                    uri = "sip:$realm",
                    nonceB64 = nonceB64,
                    opaque = wwwAuthenticateParams["opaque"],
                    akaResult = akaResult,
                ).toString()
            }

        var portS = 5060
        if (plainRegReply.headers.containsKey("security-server")) {
            val securityServer = plainRegReply.headers["security-server"]!!
            commonHeaders += ("security-verify" to securityServer)
            registerHeaders += ("security-verify" to securityServer)
            val supported_alg = listOf("hmac-sha-1-96", "hmac-md5-96")
            val supported_ealg = listOf("aes-cbc", "null")
            val (securityServerType, securityServerParams) =
                securityServer
                    .map { it.getParams() }
                    .filter {
                        val thisEAlg = it.component2()["ealg"] ?: "null"
                        supported_ealg.contains(thisEAlg)
                    }.filter { supported_alg.contains(it.component2()["alg"]) }
                    .sortedByDescending { it.component2()["q"]?.toFloat() ?: 0.toFloat() }[0]
            require(securityServerType == "ipsec-3gpp")

            portS = securityServerParams["port-s"]!!.toInt()
            val spiS = securityServerParams["spi-s"]!!.toUInt().toInt()
            val serverSpiS = ipSecManager.allocateSecurityParameterIndex(pcscfAddr, spiS)

            val spiC = securityServerParams["spi-c"]!!.toUInt().toInt()
            val serverSpiC = ipSecManager.allocateSecurityParameterIndex(pcscfAddr, spiC)

            ipsecSettings =
                SipIpsecSettings(
                    clientSpiS = clientSpiS,
                    clientSpiC = clientSpiC,
                    serverSpiC = serverSpiC,
                    serverSpiS = serverSpiS,
                )

            val ealg = securityServerParams["ealg"] ?: "null"
            val (alg, hmac_key) =
                if (securityServerParams["alg"] == "hmac-sha-1-96") {
                    IpSecAlgorithm.AUTH_HMAC_SHA1 to akaResult.ik + ByteArray(4)
                } else {
                    IpSecAlgorithm.AUTH_HMAC_MD5 to akaResult.ik
                }
            val ipSecBuilder =
                IpSecTransform
                    .Builder(ctxt)
                    .setAuthentication(IpSecAlgorithm(alg, hmac_key, 96))
                    .also {
                        if (ealg == "aes-cbc") {
                            it.setEncryption(IpSecAlgorithm(IpSecAlgorithm.CRYPT_AES_CBC, akaResult.ck))
                        }
                    }

            val serverInTransform = ipSecBuilder.buildTransportModeTransform(pcscfAddr, clientSpiS)
            val serverOutTransform = ipSecBuilder.buildTransportModeTransform(localAddr, serverSpiC)
            socket.enableIpsec(ipSecBuilder, ipSecManager, clientSpiC, serverSpiS)
            serverSocket.enableIpsec(ipSecManager, serverInTransform, serverOutTransform)
            serverSocketUdp.enableIpsec(ipSecManager, serverInTransform, serverOutTransform)
        }
        if (socket is SipConnectionTcp) (socket as SipConnectionTcp).socket.soTimeout = 15000
        socket.connect(portS)
        updateCommonHeaders(socket)
        register()
        val regReader =
            if (socket is SipConnectionTcp) {
                socket.gReader()
            } else if (socket is SipConnectionUdp) {
                serverSocketUdp.gReader()
            } else {
                socket.gReader()
            }
        var regReply = regReader.parseMessage() ?: throw EOFException("P-CSCF closed before answering REGISTER")
        var skipped = 0
        while (regReply is SipResponse && skipped++ < 8 &&
            regReply.headers["call-id"]?.getOrNull(0) != registerHeaders["call-id"]!![0]) {
            Rlog.d(TAG, "Ignoring ${regReply.statusCode} for another transaction while registering")
            regReply = regReader.parseMessage() ?: throw EOFException("P-CSCF closed before answering REGISTER")
        }
        Rlog.d(TAG, "Received $regReply")

        if (regReply !is SipResponse || regReply.statusCode != 200) {
            Rlog.w(TAG, "Could not connect, aborting SIP")
            throw IOException("Protected REGISTER got ${(regReply as? SipResponse)?.statusCode} instead of 200")
        }

        if (socket is SipConnectionTcp) (socket as SipConnectionTcp).socket.soTimeout = 0
        setResponseCallback(registerHeaders["call-id"]!![0], ::registerCallback)
        setRequestCallback(SipMethod.MESSAGE, ::handleSms)
        setRequestCallback(SipMethod.INVITE, ::handleCall)
        setRequestCallback(SipMethod.PRACK, ::handlePrack)
        setRequestCallback(SipMethod.CANCEL, ::handleCancel)
        setRequestCallback(SipMethod.BYE, ::handleCancel)
        setRequestCallback(SipMethod.UPDATE, ::handleUpdate)
        handleResponse(regReply)

        val establishedSocket = socket
        val establishedServerSocket = serverSocket
        val establishedUdpServer = serverSocketUdp
        val establishedGeneration = registrationGeneration.get()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                while (parseMessage(establishedSocket.gReader(), establishedSocket.gWriter())) { }
                Rlog.w(TAG, "Main socket got EOF, reconnecting")
            } catch (t: Throwable) {
                Rlog.w(TAG, "Got exception in main/control socket, reconnecting", t)
            }
            establishedSocket.close()
            if (socket !== establishedSocket || registrationGeneration.get() != establishedGeneration) return@launch
            reconnectWithRetry()
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                while (true) {
                    val client = establishedServerSocket.serverSocket.accept()
                    val reader = client.getInputStream().sipReader()
                    val writer = client.getOutputStream()
                    while (parseMessage(reader, writer)) { }
                    client.close()
                }
            } catch (t: Throwable) {
                Rlog.d(TAG, "Got exception in TCP server socket", t)
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bufferIn = ByteArray(128 * 1024)
                val dgramPacketIn = DatagramPacket(bufferIn, bufferIn.size)
                val writer = ByteArrayOutputStream()
                while (true) {
                    dgramPacketIn.length = bufferIn.size
                    establishedUdpServer.socket.receive(dgramPacketIn)
                    Rlog.d(TAG, "Received dgram packet")
                    val baIs = ByteArrayInputStream(dgramPacketIn.data, dgramPacketIn.offset, dgramPacketIn.length)
                    val reader = baIs.sipReader()
                    while (parseMessage(reader, writer)) { }
                    val writerOut = writer.toByteArray()
                    val dgramPacketOut = DatagramPacket(writerOut, writerOut.size, dgramPacketIn.address, dgramPacketIn.port)
                    establishedUdpServer.socket.send(dgramPacketOut)
                    writer.reset()
                }
            } catch (t: Throwable) {
                Rlog.d(TAG, "Got exception in UDP server socket", t)
            }
        }
    }

    fun getVolteNetwork() {
        Rlog.d(TAG, "Requesting IMS network")
        connectivityManager.requestNetwork(
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .setNetworkSpecifier(TelephonyNetworkSpecifier.Builder().setSubscriptionId(subId).build())
                .addCapability(NetworkCapabilities.NET_CAPABILITY_IMS)
                .build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onUnavailable() {
                    Rlog.d(TAG, "IMS network unavailable")
                }

                override fun onLost(network: Network) {
                    Rlog.d(TAG, "IMS network lost")
                    if (this@SipHandler::network.isInitialized && this@SipHandler.network == network) {
                        imsReady = false
                        abandonnedBecauseOfNoPcscf = true
                        imsFailureCallback?.invoke()
                    }
                }

                override fun onBlockedStatusChanged(
                    network: Network,
                    blocked: Boolean,
                ) {
                    Rlog.d(TAG, "IMS network blocked status changed $blocked")
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    Rlog.d(TAG, "IMS network capabilities changed $networkCapabilities")
                }

                override fun onLosing(
                    network: Network,
                    maxMsToLive: Int,
                ) {
                    Rlog.d(TAG, "IMS network losing")
                }

                override fun onLinkPropertiesChanged(
                    _network: Network,
                    linkProperties: LinkProperties,
                ) {
                    Rlog.d(TAG, "IMS network link properties changed $linkProperties")
                    if (this@SipHandler::network.isInitialized && network == _network) {
                        refreshRegistrationTech()
                    }
                    val pcscfs = linkProperties!!.javaClass.getMethod("getPcscfServers").invoke(linkProperties) as List<*>
                    Rlog.d(TAG, "Got pcscfs $pcscfs")
                    if (pcscfs.isNotEmpty() && abandonnedBecauseOfNoPcscf) {
                        network = _network
                        val generation = registrationGeneration.get()
                        try {
                            connectUnlessSuperseded(generation, "onLinkPropertiesChanged")
                        } catch (e: Throwable) {
                            Rlog.e(TAG, "connect() from onLinkPropertiesChanged failed: $e")
                            CoroutineScope(Dispatchers.IO).launch { reconnectWithRetry() }
                        }
                    }
                }

                override fun onAvailable(_network: Network) {
                    Rlog.d(TAG, "Got IMS network.")
                    if (!this@SipHandler::network.isInitialized || network != _network || abandonnedBecauseOfNoPcscf) {
                        registrationGeneration.incrementAndGet()
                        imsReady = false
                        network = _network
                        val generation = registrationGeneration.get()
                        thread {
                            Thread.sleep(4000)
                            if (network != _network) return@thread
                            try {
                                connectUnlessSuperseded(generation, "onAvailable")
                            } catch (e: Throwable) {
                                Rlog.e(TAG, "connect() failed: $e")
                                CoroutineScope(Dispatchers.IO).launch { reconnectWithRetry() }
                            }
                        }
                    } else {
                        Rlog.d(TAG, "... don't try anything")
                    }
                }
            },
        )
    }

    fun updateCommonHeaders(socket: SipConnection) {
        val local =
            if (socket.gLocalAddr() is Inet6Address) {
                "[${socket.gLocalAddr().hostAddress}]:${serverSocket.localPort}"
            } else {
                "${socket.gLocalAddr().hostAddress}:${serverSocket.localPort}"
            }

        val sipInstance = "<urn:gsma:imei:${imei.substring(0,8)}-${imei.substring(8,14)}-0>"
        val transport = if (socket is SipConnectionTcp) "tcp" else "udp"
        contact =
            """<sip:$imsi@$local;transport=$transport>;expires=600000;+sip.instance="$sipInstance";+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel";+g.3gpp.smsip;audio"""
        val newHeaders =
            (
                if (socket is SipConnectionTcp) {
                    """
                Via: SIP/2.0/TCP $local;rport
                """
                } else {
                    """
                Via: SIP/2.0/UDP $local;rport
                """
                }
            ).toSipHeadersMap()
        registerHeaders += newHeaders
        commonHeaders += newHeaders
    }

    private fun buildPAccessNetworkInfo(): String {
        if (registrationTech == ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN) {
            return "IEEE-802.11"
        }
        val cell =
            try {
                telephonyManager.serviceState?.networkRegistrationInfoList
                    ?.mapNotNull { it.cellIdentity as? CellIdentityLte }
                    ?.firstOrNull { it.ci in 0..0x0fffffff && it.tac in 0..0xffff &&
                        !it.mccString.isNullOrEmpty() && !it.mncString.isNullOrEmpty() }
            } catch (e: SecurityException) {
                Rlog.w(TAG, "No location access for cell identity", e)
                null
            }
        if (cell == null) {
            Rlog.d(TAG, "P-Access-Network-Info: no usable LTE cell identity, access type only")
            return "3GPP-E-UTRAN-FDD"
        }
        val cellId = cell.mccString + cell.mncString +
            "%04x%07x".format(java.util.Locale.ROOT, cell.tac, cell.ci)
        Rlog.d(TAG, "P-Access-Network-Info: utran-cell-id-3gpp=$cellId")
        return "3GPP-E-UTRAN-FDD;utran-cell-id-3gpp=$cellId"
    }

    @SuppressLint("MissingPermission")
    fun register(_writer: OutputStream? = null) {
        val tm = ctxt.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        val cellInfoList = tm.getAllCellInfo()
        for (cell in cellInfoList) {
            if (cell is CellInfoLte) {
                val cellIdentity = cell.cellIdentity
                val cellSignalStrength = cell.cellSignalStrength
                Rlog.d(
                    TAG,
                    "LTE cell: ${cellIdentity.ci}, ${cellIdentity.pci}, ${cellIdentity.tac}, ${cellIdentity.mcc}, ${cellIdentity.mnc}, ${cellSignalStrength.dbm}",
                )
            } else if (cell is CellInfoNr) {
                val cellIdentity = cell.cellIdentity
                val cellSignalStrength = cell.cellSignalStrength
                Rlog.d(TAG, "NR cell: ${cellIdentity.operatorAlphaLong}, ${cellIdentity.operatorAlphaShort}, $cellIdentity")
            } else if (cell is CellInfoWcdma) {
                val cellIdentity = cell.cellIdentity
                val cellSignalStrength = cell.cellSignalStrength
                Rlog.d(
                    TAG,
                    "WCDMA cell: ${cellIdentity.cid}, ${cellIdentity.lac}, ${cellIdentity.mcc}, ${cellIdentity.mnc}, ${cellSignalStrength.dbm}",
                )
            } else if (cell is CellInfoGsm) {
                val cellIdentity = cell.cellIdentity
                val cellSignalStrength = cell.cellSignalStrength
                Rlog.d(
                    TAG,
                    "GSM cell: ${cellIdentity.cid}, ${cellIdentity.lac}, ${cellIdentity.mcc}, ${cellIdentity.mnc}, ${cellSignalStrength.dbm}",
                )
            }
        }

        val access = listOf(buildPAccessNetworkInfo())
        registerHeaders += "p-access-network-info" to access
        commonHeaders += "p-access-network-info" to access
        val writer = _writer ?: socket.gWriter()

        fun secClient(
            alg: String,
            ealg: String,
        ) =
            "ipsec-3gpp;prot=esp;mod=trans;spi-c=${ipsecSettings.clientSpiC.spi};spi-s=${ipsecSettings.clientSpiS.spi};port-c=${socket.gLocalPort()};port-s=${serverSocket.localPort};ealg=$ealg;alg=$alg"

        val algs = listOf("hmac-sha-1-96", "hmac-md5-96")
        val ealgs = listOf("null", "aes-cbc")
        val secClients = algs.flatMap { alg -> ealgs.map { ealg -> secClient(alg, ealg) } }
        val secClientLine =
            "Security-Client: ${secClients.joinToString(", ")}"

        val msg =
            SipRequest(
                SipMethod.REGISTER,
                "sip:$realm",
                registerHeaders +
                    """
                    Expires: 600000
                    Cseq: $registerCounter REGISTER
                    Contact: $contact
                    Supported: path, gruu, sec-agree
                    Allow: INVITE, ACK, CANCEL, BYE, UPDATE, REFER, NOTIFY, MESSAGE, PRACK, OPTIONS
                    Authorization: $akaDigest
                    Require: sec-agree
                    Proxy-Require: sec-agree
                    $secClientLine
                    """.toSipHeadersMap(),
            ) // route present on all calls except this
        Rlog.d(TAG, "Sending $msg")
        synchronized(writer) { writer.write(msg.toByteArray()) }
        registerCounter += 1
    }

    fun registerCallback(response: SipResponse): Boolean {
        require(response.statusCode == 200)

        val r = Regex("lr;[^>]*")
        val route =
            (
                response.headers.getOrDefault("service-route", emptyList()) +
                    response.headers.getOrDefault("path", emptyList())
            ).toSet() // remove duplicates
                .toList()
                .map {
                    r.replace(it, "lr")
                }

        val associatedUri =
            response.headers["p-associated-uri"]!!
                .flatMap { it.split(",") }
                .map { it.trimStart('<').trimEnd('>').split(':') }
        val preSip = associatedUri.first { it[0] == "sip" }[1]

        mySip = "sip:" + preSip
        myTel = associatedUri.firstOrNull { it[0] == "tel" }?.get(1) ?: preSip.split("@")[0]
        commonHeaders +=
            mapOf(
                "route" to route,
                "from" to listOf("<$mySip>"),
                "to" to listOf("<$mySip>"),
            )

        subscribe()
        return false
    }

    private fun dialogContact(
        local: String,
        transport: String,
    ): String =
        """<sip:$imsi@$local;transport=$transport>;expires=600000;+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel";+g.3gpp.smsip;audio"""

    fun subscribe() {
        val local =
            if (socket.gLocalAddr() is Inet6Address) {
                "[${socket.gLocalAddr().hostAddress}]:${serverSocket.localPort}"
            } else {
                "${socket.gLocalAddr().hostAddress}:${serverSocket.localPort}"
            }
        val transport = if (socket is SipConnectionTcp) "tcp" else "udp"
        val contactTel = dialogContact(local, transport)
        val msg =
            SipRequest(
                SipMethod.SUBSCRIBE,
                "$mySip",
                commonHeaders +
                    """
                    Contact: $contactTel
                    P-Preferred-Identity: <$mySip>
                    Event: reg
                    Expires: 600000
                    Supported: sec-agree
                    Require: sec-agree
                    Proxy-Require: sec-agree
                    Allow: INVITE, ACK, CANCEL, BYE, UPDATE, REFER, NOTIFY, INFO, MESSAGE, PRACK, OPTIONS
                    Accept: application/reginfo+xml
                    """.toSipHeadersMap(),
            )
        if (!imsReady) {
            setResponseCallback(msg.headers["call-id"]!![0], ::subscribeCallback)
        }
        Rlog.d(TAG, "Sending $msg")
        synchronized(socket.gWriter()) { socket.gWriter().write(msg.toByteArray()) }
    }

    fun subscribeCallback(response: SipResponse): Boolean {
        imsReadyCallback?.invoke()
        imsReady = true
        return true
    }

    fun waitPrack(v: Int, generation: Int = callGeneration.get()): Boolean {
        synchronized(prAckWaitLock) {
            while (prAckWait.contains(v) && !callStopped.get() && callGeneration.get() == generation) {
                prAckWaitLock.wait(1000)
            }
            return !callStopped.get() && callGeneration.get() == generation
        }
    }

    fun handlePrack(request: SipRequest): Int {
        Rlog.d(TAG, "Received PRACK for ${request.headers["rack"]!![0]}")
        synchronized(prAckWaitLock) {
            val id = request.headers["rack"]!![0].split(" ")[0].toInt()
            prAckWait -= id
            prAckWaitLock.notifyAll()
        }
        return 200
    }

    fun handleUpdate(request: SipRequest): Int {
        val call = currentCall!!
        val ipType = if (call.rtpRemoteAddr is Inet6Address) "IP6" else "IP4"
        val allTracks = listOf(call.amrTrack, call.dtmfTrack)
        call.sdpOrigin.revise()
        val mySdp =
            """
v=0
${call.sdpOrigin.line(ipType, socket.gLocalAddr().hostAddress)}
s=phh voice call
c=IN $ipType ${socket.gLocalAddr().hostAddress}
b=AS:38
b=RS:475
b=RR:1425
t=0 0
m=audio ${call.rtpSocket.localPort} RTP/AVP ${allTracks.joinToString(" ")}
b=AS:38
b=RS:475
b=RR:1425
a=rtpmap:${call.amrTrack} AMR/8000
a=${call.amrTrackDesc}
a=rtpmap:${call.dtmfTrack} telephone-event/8000
a=${call.dtmfTrackDesc}
a=ptime:20
a=maxptime:240
a=curr:qos local sendrecv
a=curr:qos remote sendrecv
a=des:qos mandatory local sendrecv
a=des:qos mandatory remote sendrecv
a=sendrecv
                       """.toSdpBody().let(::labSdp)

        currentCall =
            Call(
                outgoing = call.outgoing,
                amrTrack = call.amrTrack,
                amrTrackDesc = call.amrTrackDesc,
                dtmfTrack = call.dtmfTrack,
                dtmfTrackDesc = call.dtmfTrackDesc,
                callHeaders = call.callHeaders,
                rtpRemoteAddr = call.rtpRemoteAddr,
                rtpRemotePort = call.rtpRemotePort,
                rtpSocket = call.rtpSocket,
                sdp = request.body,
                sdpOrigin = call.sdpOrigin,
                hasEarlyMedia = call.hasEarlyMedia,
                remoteContact = call.remoteContact,
            )

        val reply =
            SipResponse(
                statusCode = 200,
                statusString = "OK",
                headersParam =
                    request.headers.filter { (k, _) ->
                        k in listOf("cseq", "via", "from", "to", "call-id")
                    } +
                        """
                    Content-Type: application/sdp
                    Supported: 100rel, replaces, timer
                    Require: precondition
                    Call-ID: ${currentCall!!.callHeaders["call-id"]!![0]}
                """.toSipHeadersMap(),
                body = mySdp,
            )
        Rlog.d(TAG, "Replying back with $reply")
        synchronized(socket.gWriter()) { socket.gWriter().write(reply.toByteArray()) }

        if (call?.outgoing == false) {
            val myHeaders2 = call.callHeaders - "rseq" - "content-type" - "require"
            val msg2 =
                SipResponse(
                    statusCode = 180,
                    statusString = "Ringing",
                    headersParam = myHeaders2,
                )
            Rlog.d(TAG, "Sending $msg2")
            synchronized(socket.gWriter()) { socket.gWriter().write(msg2.toByteArray()) }
        }

        return 0
    }

    fun handleCancel(request: SipRequest): Int {
        if (request.method == SipMethod.CANCEL && callStarted.get()) {
            Rlog.d(TAG, "CANCEL received after 200 OK — ignoring per RFC 3261 §9.2")
            return 200
        }
        callStopped.set(true)
        closeMediaSockets()
        val endedCallId = request.headers["call-id"]!![0]
        Rlog.d(TAG, "Cancelled call $endedCallId")
        if (currentCall?.callHeaders?.get("call-id")?.firstOrNull() == endedCallId) currentCall = null

        onCancelledCall?.invoke(Object(), "", emptyMap())
        return 200
    }

    data class Call(
        val outgoing: Boolean,
        val callHeaders: SipHeadersMap,
        val sdp: ByteArray,
        val sdpOrigin: SdpOrigin,
        val amrTrack: Int,
        val amrTrackDesc: String,
        val dtmfTrack: Int,
        val dtmfTrackDesc: String,
        val rtpRemoteAddr: InetAddress,
        val rtpRemotePort: Int,
        val rtpSocket: DatagramSocket,
        val hasEarlyMedia: Boolean,
        val remoteContact: String,
    )

    private fun mediaThread(
        what: String,
        body: () -> Unit,
    ) {
        val generation = callGeneration.get()
        thread {
            try {
                body()
            } catch (e: Throwable) {
                if (callStopped.get() || callGeneration.get() != generation) return@thread
                Rlog.e(TAG, "$what thread failed, ending the call", e)
                try {
                    terminateCall()
                } catch (e2: Throwable) {
                    Rlog.e(TAG, "terminateCall() after $what failure also failed", e2)
                    callStopped.set(true)
        closeMediaSockets()
                }
                onCancelledCall?.invoke(Object(), "", emptyMap())
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun callEncodeThread() {
        val call = currentCall!!
        val gen = callGeneration.get()
        mediaThread("encode") {
            var sequenceNumber = 0

            Rlog.d(TAG, "Encode thread started: amrTrack=${call.amrTrack} remote=${call.rtpRemoteAddr}:${call.rtpRemotePort} gen=$gen")
            val encoder = MediaCodec.createEncoderByType("audio/3gpp")
            val mediaFormat = MediaFormat.createAudioFormat("audio/3gpp", 8000, 1)
            mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, AMR_NB_BIT_RATE)
            encoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            while (!callStarted.get()) {
                if (callStopped.get() || callGeneration.get() != gen) {
                    Rlog.d(TAG, "Silence loop exiting early: callStopped=${callStopped.get()}, genMismatch=${callGeneration.get() != gen}")
                    encoder.stop()
                    encoder.release()
                    return@mediaThread
                }
                val timestamp = sequenceNumber * 160
                Thread.sleep(20)
                val rtpHeader =
                    listOf(
                        0x80, // rtp version
                        call.amrTrack, // payload type
                        (sequenceNumber shr 8),
                        (sequenceNumber and 0xff),
                        (timestamp shr 24),
                        ((timestamp shr 16) and 0xff),
                        ((timestamp shr 8) and 0xff),
                        (timestamp and 0xff),
                        0x03,
                        0x00,
                        0xd2,
                        0x00, // SSRC
                    )
                val amrNothing = listOf(0x77, 0xc0) // CMR = 12.2kbps, F=0, FT=15=No TX/No RX, Q=1

                val buf = (rtpHeader + amrNothing).map { it.toUByte() }.toUByteArray().toByteArray()

                val dgramPacket =
                    DatagramPacket(buf, buf.size, call.rtpRemoteAddr, call.rtpRemotePort)
                call.rtpSocket.send(dgramPacket)
                sequenceNumber++
            }
            Rlog.d(TAG, "Silence loop exited after $sequenceNumber packets, starting real encoding")

            val minBufferSize = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val audioRecord =
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    8000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufferSize,
                )
            Rlog.d(TAG, "AudioRecord created with minBufferSize=$minBufferSize, state=${audioRecord.state}")

            val audioManager = ctxt.getSystemService(android.media.AudioManager::class.java)
            val builtinMic =
                audioManager
                    .getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            if (builtinMic != null) {
                audioRecord.preferredDevice = builtinMic
                Rlog.d(TAG, "AudioRecord preferredDevice set to builtin mic: id=${builtinMic.id} name=${builtinMic.productName}")
            } else {
                Rlog.w(TAG, "AudioRecord: no TYPE_BUILTIN_MIC found, proceeding without preferredDevice")
            }

            val prevAudioMode = audioManager.mode
            audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            audioRecord.startRecording()
            Rlog.d(
                TAG,
                "AudioRecord started, state=${audioRecord.recordingState} audioMode=${audioManager.mode} (was $prevAudioMode) preferredDevice=${audioRecord.preferredDevice?.type}",
            )

            var firstPacket = true
            var realFrameCount = 0

            val buffer = ByteArray(minBufferSize)
            while (true) {
                if (callStopped.get() || callGeneration.get() != gen) break
                val nRead = audioRecord.read(buffer, 0, buffer.size)
                if (realFrameCount < 5) {
                    val allZero = buffer.take(nRead.coerceAtLeast(0)).all { it == 0.toByte() }
                    Rlog.d(TAG, "AudioRecord.read nRead=$nRead allZero=$allZero (bufferSize=${buffer.size})")
                }

                val inBufIdx = encoder.dequeueInputBuffer(-1)
                val inBuf = encoder.getInputBuffer(inBufIdx)!!
                inBuf.clear()
                inBuf.put(buffer, 0, nRead)

                encoder.queueInputBuffer(inBufIdx, 0, nRead, System.nanoTime() / 1000, 0)

                val outBufInfo = MediaCodec.BufferInfo()
                var drainTimeout = -1L
                var outCount = 0
                while (true) {
                    val outBufIdx = encoder.dequeueOutputBuffer(outBufInfo, drainTimeout)
                    drainTimeout = 0L
                    if (outBufIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Rlog.d(TAG, "Encoder output format changed")
                        continue
                    }
                    if (outBufIdx < 0) {
                        if (outCount > 0) Rlog.d(TAG, "Drained $outCount output buffers")
                        break
                    }
                    outCount++

                    val outBuf = encoder.getOutputBuffer(outBufIdx)!!

                    val encoderData = ByteArray(outBufInfo.size)
                    outBuf.get(encoderData)
                    encoder.releaseOutputBuffer(outBufIdx, false)

                    if (realFrameCount == 0) {
                        Rlog.d(
                            TAG,
                            "First encoder output: size=${outBufInfo.size} raw=${encoderData.take(
                                32,
                            ).joinToString(" ") { "%02x".format(it) }}",
                        )
                    }

                    var bufPos = 0
                    while (bufPos < outBufInfo.size) {
                        val frameSize = 32
                        if (outBufInfo.size - bufPos < frameSize) break

                        val ft = (encoderData[bufPos].toUByte().toInt() shr 3) and 0xf
                        val q = (encoderData[bufPos].toUByte().toInt() shr 2) and 0x1

                        val cmr = 0xf
                        val f = 0
                        val beByte0 = (cmr shl 4) or (f shl 3) or (ft shr 1)
                        val beByte1 =
                            ((ft and 1) shl 7) or (q shl 6) or
                                (encoderData[bufPos + 1].toUByte().toInt() shr 2)
                        val beRest =
                            (1 until frameSize - 1).map { i ->
                                val lo = (encoderData[bufPos + i].toUByte().toInt() and 0x3) shl 6
                                val hi = (encoderData[bufPos + i + 1].toUByte().toInt() shr 2) and 0x3f
                                lo or hi
                            }

                        val timestamp = sequenceNumber * 160
                        val rtpHeader =
                            byteArrayOf(
                                0x80.toByte(),
                                ((if (firstPacket) 0x80 else 0) or call.amrTrack).toByte(),
                                (sequenceNumber shr 8).toByte(),
                                (sequenceNumber and 0xff).toByte(),
                                (timestamp shr 24).toByte(),
                                ((timestamp shr 16) and 0xff).toByte(),
                                ((timestamp shr 8) and 0xff).toByte(),
                                (timestamp and 0xff).toByte(),
                                0x03,
                                0x00,
                                0xd2.toByte(),
                                0x00,
                            )
                        firstPacket = false

                        val buf =
                            rtpHeader +
                                byteArrayOf(beByte0.toByte(), beByte1.toByte()) +
                                beRest.map { it.toByte() }.toByteArray()

                        val dgramPacket = DatagramPacket(buf, buf.size, call.rtpRemoteAddr, call.rtpRemotePort)
                        try {
                            call.rtpSocket.send(dgramPacket)
                            if (realFrameCount < 10) {
                                Rlog.d(
                                    TAG,
                                    "Sent RTP packet #$sequenceNumber ft=$ft ts=$timestamp payload=${buf.drop(
                                        12,
                                    ).take(4).joinToString(" ") { "%02x".format(it) }}... to ${call.rtpRemoteAddr}:${call.rtpRemotePort}",
                                )
                            }
                            if (realFrameCount == 0) {
                                Rlog.d(TAG, "First RTP packet full hex: ${buf.joinToString(" ") { "%02x".format(it) }}")
                            }
                            if (sequenceNumber % 50 == 0 && realFrameCount >= 10) {
                                Rlog.d(
                                    TAG,
                                    "Sent RTP packet #$sequenceNumber ft=$ft ts=$timestamp to ${call.rtpRemoteAddr}:${call.rtpRemotePort}",
                                )
                            }
                        } catch (e: Exception) {
                            Rlog.e(TAG, "Failed to send RTP packet #$sequenceNumber: ${e.message}", e)
                        }

                        sequenceNumber++
                        realFrameCount++
                        bufPos += frameSize
                    }
                }
            }
            Rlog.d(
                TAG,
                "Encode thread exiting: callStopped=${callStopped.get()}, genMismatch=${callGeneration.get() != gen}, totalPacketsSent=$sequenceNumber",
            )
            audioRecord.stop()
            audioRecord.release()
            encoder.stop()
            encoder.release()
            audioManager.mode = android.media.AudioManager.MODE_NORMAL
        }
    }

    var currentCall: Call? = null

    fun acceptCall() {
        val call = currentCall ?: return
        val generation = callGeneration.get()
        thread {
            val pendingSeqs = synchronized(prAckWaitLock) { prAckWait.toSet() }
            pendingSeqs.forEach { if (!waitPrack(it, generation)) return@thread }
            if (callStopped.get() || callGeneration.get() != generation || currentCall !== call) return@thread

            val local =
                if (socket.gLocalAddr() is Inet6Address) {
                    "[${socket.gLocalAddr().hostAddress}]:${serverSocket.localPort}"
                } else {
                    "${socket.gLocalAddr().hostAddress}:${serverSocket.localPort}"
                }
            val transport = if (socket is SipConnectionTcp) "tcp" else "udp"
            val evolvedContact =
                """<sip:$imsi@$local;transport=$transport>;expires=600000;+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel";+g.3gpp.smsip;+g.3gpp.mid-call;+g.3gpp.srvcc-alerting;+g.3gpp.ps2cs-srvcc-orig-pre-alerting"""

            Rlog.d(TAG, "Accepting call")
            val myHeaders = call.callHeaders
            val myHeaders3 =
                myHeaders - "rseq" - "security-verify" +
                    """
                Session-Expires: 900;refresher=uas
                P-Preferred-Identity: <$mySip>
                Contact: $evolvedContact
                Content-Type: application/sdp
                """.toSipHeadersMap()

            val msg3 =
                SipResponse(
                    statusCode = 200,
                    statusString = "OK",
                    headersParam = myHeaders3,
                    body = call.sdp,
                )
            Rlog.d(TAG, "Sending $msg3")
            synchronized(socket.gWriter()) { socket.gWriter().write(msg3.toByteArray()) }

            callStarted.set(true)
        }
    }

    fun prack(resp: SipResponse) {
        val who = extractDestinationFromContact(resp.headers["contact"]!![0])
        val callId = resp.headers["call-id"]!![0]
        val rseq = resp.headers["rseq"]!![0]
        val whatToPrack = "$rseq ${resp.headers["cseq"]!![0]}"

        val requiresPrecondition =
            resp.headers["require"]?.any { it.contains("precondition") } == true
        val requireTags = if (requiresPrecondition) "sec-agree, precondition" else "sec-agree"
        if (requiresPrecondition)
            Rlog.d(TAG, "PRACK: response requires precondition, echoing the option tag")
        val dialogRoute = resp.headers.uacDialogRoute()
        val headers = (commonHeaders - "route") + ("route" to dialogRoute)
        val msg =
            SipRequest(
                SipMethod.PRACK,
                who,
                headersParam =
                    headers +
                        """
                    RAck: $whatToPrack
                    Require: $requireTags
                    Proxy-Require: sec-agree
                    To: ${resp.headers["to"]!![0]}
                    From: ${resp.headers["from"]!![0]}
                    Call-Id: $callId
                    """.toSipHeadersMap(),
            )
        Rlog.d(TAG, "Sending $msg")
        synchronized(socket.gWriter()) { socket.gWriter().write(msg.toByteArray()) }
    }

    fun rejectCall() {
        thread {
            val call = currentCall!!
            val headers = call.callHeaders
            val mySeqCounter = reliableSequenceCounter++
            val myHeaders = headers + "RSeq: $mySeqCounter".toSipHeadersMap()
            val msg =
                SipResponse(
                    statusCode = 486,
                    statusString = "Busy Here",
                    headersParam = myHeaders,
                )
            Rlog.d(TAG, "Sending $msg")
            synchronized(socket.gWriter()) { socket.gWriter().write(msg.toByteArray()) }

            callStopped.set(true)
        closeMediaSockets()
            onCancelledCall?.invoke(Object(), "", emptyMap())
        }
    }

    @Volatile private var pendingInvite: SipRequest? = null
    @Volatile private var cancelledCallId: String? = null

    private fun sendCancel(invite: SipRequest) {
        val kept = listOf("via", "route", "from", "to", "call-id", "max-forwards", "p-access-network-info")
        val headers = invite.headers.filterKeys { it in kept } +
            ("cseq" to listOf(invite.headers["cseq"]!![0].substringBefore(' ') + " CANCEL"))
        val cancel = SipRequest(SipMethod.CANCEL, invite.destination, headers)
        Rlog.d(TAG, "Sending CANCEL $cancel")
        synchronized(socket.gWriter()) { socket.gWriter().write(cancel.toByteArray()) }
    }

    fun terminateCall() {
        callStopped.set(true)
        closeMediaSockets()
        val invite = pendingInvite
        if (invite != null && !callStarted.get()) {
            cancelledCallId = invite.headers["call-id"]!![0]
            pendingInvite = null
            sendCancel(invite)
            return
        }
        val call = currentCall ?: return
        currentCall = null
        val byeHeaders = call.callHeaders.filterKeys { it != "content-type" }
        val bye =
            SipRequest(
                SipMethod.BYE,
                call.remoteContact,
                headersParam = byeHeaders,
            )
        Rlog.d(TAG, "Sending BYE $bye")
        synchronized(socket.gWriter()) { socket.gWriter().write(bye.toByteArray()) }
    }

    var respInFlight: SipResponse? = null

    fun call(phoneNumber: String) {
        thread {
            callStopped.set(false)
            callStarted.set(false)
            threadsStarted.set(false)
            callGeneration.incrementAndGet()
            currentCall = null
            pendingInvite = null

            val rtpSocket = openMediaSocket()
            Rlog.d(TAG, "RTP socket created for outgoing call: local=${rtpSocket.localAddress}:${rtpSocket.localPort}")

            val amrTrack = 96
            val amrTrackDesc = "fmtp:96 max-red=0; mode-change-capability=2; mode-change-neighbor=1; mode-change-period=2; octet-align=0"
            val dtmfTrack = 100
            val dtmfTrackDesc = "fmtp:100 0-15"
            val allTracks = listOf(amrTrack, dtmfTrack)
            val sdpOrigin = SdpOrigin.next()

            val ipType = if (localAddr is Inet6Address) "IP6" else "IP4"

            val sdp =
                """
v=0
${sdpOrigin.line(ipType, socket.gLocalAddr().hostAddress)}
s=phh voice call
c=IN $ipType ${socket.gLocalAddr().hostAddress}
b=AS:38
b=RS:475
b=RR:1425
t=0 0
m=audio ${rtpSocket.localPort} RTP/AVP ${allTracks.joinToString(" ")}
b=AS:38
b=RS:475
b=RR:1425
a=ptime:20
a=maxptime:240
a=rtpmap:$amrTrack AMR/8000
a=$amrTrackDesc
a=rtpmap:$dtmfTrack telephone-event/8000
a=$dtmfTrackDesc
a=sendrecv
                       """.toSdpBody().let(::labSdp)

            val normalized = PhoneNumberUtils.normalizeNumber(phoneNumber)
            val global = PhoneNumberUtils.formatNumberToE164(normalized,
                telephonyManager.simCountryIso.uppercase(java.util.Locale.ROOT))
            val to = if (global != null) "tel:$global"
                else "tel:$normalized;phone-context=$realm"
            val local =
                if (socket.gLocalAddr() is Inet6Address) {
                    "[${socket.gLocalAddr().hostAddress}]:${serverSocket.localPort}"
                } else {
                    "${socket.gLocalAddr().hostAddress}:${serverSocket.localPort}"
                }
            val transport = if (socket is SipConnectionTcp) "tcp" else "udp"
            val contactTel = dialogContact(local, transport)
            val myHeaders =
                commonHeaders +
                    """
                    From: <$mySip>
                    To: <$to>
                    P-Preferred-Identity: <$mySip>
                    P-Asserted-Identity: <$mySip>
                    Expires: 600000
                    Require: sec-agree
                    Proxy-Require: sec-agree
                    Allow: INVITE, ACK, CANCEL, BYE, UPDATE, REFER, NOTIFY, MESSAGE, PRACK, OPTIONS
                    P-Early-Media: supported
                    Content-Type: application/sdp
                    Session-Expires: 900
                    Supported: 100rel, replaces, timer
                    Accept: application/sdp
                    Min-SE: 90
                    Accept-Contact: *;+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel"
                    P-Preferred-Service: urn:urn-7:3gpp-service.ims.icsi.mmtel
                    Contact: $contactTel
                    """.toSipHeadersMap() + generateCallId() - "p-asserted-identity"
            val msg =
                SipRequest(
                    SipMethod.INVITE,
                    to,
                    myHeaders,
                    sdp,
                )
            setResponseCallback(msg.headers["call-id"]!![0]) { r: SipResponse ->
                var resp = r
                var cseq = resp.headers["cseq"]!![0]

                var rseqHandled = false
                if (cseq.contains("PRACK")) {
                    if (resp.statusCode < 200) return@setResponseCallback false
                    if (resp.statusCode >= 300) {
                        Rlog.w(TAG, "PRACK failed with ${resp.statusCode}; not treating it as media acceptance")
                        respInFlight = null
                        return@setResponseCallback false
                    }
                    resp = respInFlight ?: return@setResponseCallback false
                    respInFlight = null
                    cseq = resp.headers["cseq"]!![0]
                    rseqHandled = true
                }

                if (cseq.contains("ACK")) return@setResponseCallback false

                if (cseq.contains("INVITE") && (resp.statusCode == 200 || resp.statusCode == 202)) {
                    val cseqLine = resp.headers["cseq"]!![0]
                    val cseq = cseqLine.split(" ")[0].toInt()
                    val newTo = resp.headers["to"]!![0]
                    val newFrom = resp.headers["from"]!![0]
                    val ackTo =
                        resp.headers["contact"]
                            ?.get(0)
                            ?.let { extractDestinationFromContact(it) } ?: to
                    val dialogRoute = resp.headers.uacDialogRoute()
                    val ackHeaders = (myHeaders - "route") + ("route" to dialogRoute)
                    val msg2 =
                        SipRequest(
                            SipMethod.ACK,
                            ackTo,
                            ackHeaders - "content-type" +
                                """
                                CSeq: $cseq ACK
                                To: $newTo
                                From: $newFrom
                                """.toSipHeadersMap(),
                        )
                    Rlog.d(TAG, "Sending $msg2")
                    synchronized(socket.gWriter()) { socket.gWriter().write(msg2.toByteArray()) }
                    if (pendingInvite === msg) pendingInvite = null
                    if (cancelledCallId == msg.headers["call-id"]!![0]) {
                        val bye = SipRequest(SipMethod.BYE, ackTo, ackHeaders - "content-type" +
                            ("to" to listOf(newTo)) + ("from" to listOf(newFrom)))
                        Rlog.d(TAG, "Answered after local CANCEL, sending BYE $bye")
                        synchronized(socket.gWriter()) { socket.gWriter().write(bye.toByteArray()) }
                        rtpSocket.close()
                        return@setResponseCallback true
                    }
                    callStarted.set(true)
                    val rrFrom200Ok = resp.headers.uacDialogRoute()
                    run {
                        currentCall =
                            currentCall?.copy(
                                callHeaders = currentCall!!.callHeaders + ("route" to rrFrom200Ok),
                            )
                    }
                    Rlog.d(TAG, "Invite got SUCCESS")
                    onOutgoingCallConnected?.invoke(Object(), emptyMap())
                } else {
                    Rlog.d(TAG, "Invite got status ${resp.statusCode} = ${resp.statusString}")
                    if (cseq.contains("INVITE") && resp.statusCode >= 300) {
                        val ack = SipRequest(SipMethod.ACK, msg.destination,
                            msg.headers - "content-type" - "content-length" + mapOf(
                                "cseq" to listOf(msg.headers["cseq"]!![0].substringBefore(' ') + " ACK"),
                                "to" to resp.headers["to"].orEmpty()))
                        synchronized(socket.gWriter()) { socket.gWriter().write(ack.toByteArray()) }
                        Rlog.d(TAG, "Acknowledged failed INVITE: ${resp.statusCode}")
                        callStopped.set(true)
        closeMediaSockets()
                        rtpSocket.close()
                        currentCall = null
                        if (pendingInvite === msg) pendingInvite = null
                        if (cancelledCallId == msg.headers["call-id"]!![0]) {
                            Rlog.d(TAG, "INVITE ended with ${resp.statusCode} after local CANCEL")
                            return@setResponseCallback true
                        }
                        val reason = resp.headers["reason"]?.joinToString("; ") ?: resp.statusString
                        onOutgoingCallFailed?.invoke(resp.statusCode, reason)
                        return@setResponseCallback true
                    }
                    if (cseq.contains("INVITE") && resp.statusCode in listOf(180, 183)) {
                        val earlyMedia = resp.headers["p-early-media"].orEmpty()
                            .any { it.contains("sendrecv") || it.contains("sendonly") }
                        onOutgoingCallProgressing?.invoke(earlyMedia)
                    }
                }

                if (resp.headers["rseq"]?.isNotEmpty() == true && !rseqHandled) {
                    prack(resp)
                    respInFlight = resp
                    return@setResponseCallback false
                }

                val isSdp = resp.headers["content-type"]?.get(0) == "application/sdp"
                val isPrecondition = resp.headers["require"]?.find { it.contains("precondition") } != null

                if (!isSdp) return@setResponseCallback false

                val respSdp =
                    resp.body
                        .toString(Charsets.UTF_8)
                        .split("[\r\n]+".toRegex())
                        .toList()

                fun sdpElement(command: String): String? {
                    val v = respSdp.firstOrNull { it.startsWith("$command=") } ?: return null
                    return v.substring(2)
                }
                val rtpRemotePort = sdpElement("m")!!.split(" ")[1]
                val rtpRemoteAddr = InetAddress.getByName(sdpElement("c")!!.split(" ")[2])
                currentCall =
                    Call(
                        outgoing = true,
                        amrTrack = amrTrack,
                        amrTrackDesc = amrTrackDesc,
                        dtmfTrack = dtmfTrack,
                        dtmfTrackDesc = dtmfTrackDesc,
                        callHeaders =
                            myHeaders - "require" - "content-type" - "route" +
                                ("route" to resp.headers.uacDialogRoute()) + ("from" to resp.headers["from"]!!) + ("to" to resp.headers["to"]!!) +
                                ("call-id" to resp.headers["call-id"]!!),
                        rtpRemoteAddr = rtpRemoteAddr,
                        rtpRemotePort = rtpRemotePort.toInt(),
                        rtpSocket = rtpSocket,
                        sdp = resp.body,
                        sdpOrigin = sdpOrigin,
                        hasEarlyMedia = resp.headers["p-early-media"]?.isNotEmpty() == true,
                        remoteContact = extractDestinationFromContact(resp.headers["contact"]!![0]),
                    )

                if (resp.headers["cseq"]?.get(0)?.contains("UPDATE") == true) {
                    if (isSdp && resp.statusCode == 200) {
                        return@setResponseCallback false
                    }
                }

                if (isPrecondition && resp.statusCode == 183) {
                    Rlog.d(TAG, "Handling precondition...")
                    val currLocal = respSdp.firstOrNull { it.startsWith("a=curr:qos local") }
                    if (currLocal == null) {
                        Rlog.w(TAG, "precondition required but the answer carries no a=curr:qos; proceeding without it")
                        if (threadsStarted.compareAndSet(false, true)) {
                            callDecodeThread()
                            callEncodeThread()
                        }
                        return@setResponseCallback false
                    }
                    val localNone = currLocal.contains("none")
                    Rlog.d(TAG, "precondition: Curr is $currLocal $localNone")
                    val currRemote = respSdp.firstOrNull { it.startsWith("a=curr:qos remote") }
                    val remoteNone = currRemote?.contains("none") ?: true

                    if (localNone) {
                        if (threadsStarted.compareAndSet(false, true)) {
                            callDecodeThread()
                            callEncodeThread()
                        }

                        val newSdp =
                            respSdp
                                .map { line ->
                                    if (line.startsWith("a=curr:qos local")) {
                                        "a=curr:qos local sendrecv"
                                    } else if (line.startsWith("a=des:qos mandatory local")) {
                                        "a=des:qos mandatory local sendrecv"
                                    } else {
                                        line
                                    }
                                }.toSdpBody().let(::labSdp)

                        val msg2 =
                            SipRequest(
                                SipMethod.UPDATE,
                                to,
                                currentCall!!.callHeaders + ("content-type" to listOf("application/sdp")),
                                newSdp,
                            )
                        Rlog.d(TAG, "Sending $msg2")
                        synchronized(socket.gWriter()) { socket.gWriter().write(msg2.toByteArray()) }
                    }

                    return@setResponseCallback false
                }

                if (!isPrecondition && resp.statusCode == 183) {
                    if (threadsStarted.compareAndSet(false, true)) {
                        callDecodeThread()
                        callEncodeThread()
                    }
                }

                if (resp.statusCode == 200 && cseq.contains("INVITE") &&
                    threadsStarted.compareAndSet(false, true)) {
                    Rlog.d(TAG, "SDP answer in 200 OK: starting media")
                    callDecodeThread()
                    callEncodeThread()
                }

                false // Return true when we want to stop receiving messages for that call
            }
            pendingInvite = msg
            Rlog.d(TAG, "Sending $msg")
            synchronized(socket.gWriter()) { socket.gWriter().write(msg.toByteArray()) }
        }
    }

    fun callDecodeThread() {
        val call = currentCall!!
        val gen = callGeneration.get()
        mediaThread("decode") {
            val minBufferSize = AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val audioTrack =
                AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    8000,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufferSize,
                    AudioTrack.MODE_STREAM,
                )
            audioTrack.play()

            val decoder = MediaCodec.createDecoderByType("audio/3gpp")
            val mediaFormat = MediaFormat.createAudioFormat("audio/3gpp", 8000, 1)
            decoder.configure(mediaFormat, null, null, 0)
            decoder.start()

            var receivedCount = 0
            var expectedTs = -1L
            val outBufInfo = MediaCodec.BufferInfo()
            while (true) {
                if (callStopped.get() || callGeneration.get() != gen) break
                val dgramBuf = ByteArray(2048)
                val dgram = DatagramPacket(dgramBuf, dgramBuf.size)
                try {
                    call.rtpSocket.receive(dgram)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (e: java.net.SocketException) {
                    if (callStopped.get() || callGeneration.get() != gen) break
                    throw e
                }
                receivedCount++

                val pt = dgramBuf[1].toUByte().toInt() and 0x7f
                val ft = (dgramBuf[13].toUByte().toUInt() shr 7) or ((dgramBuf[12].toUByte().toUInt() and (7).toUInt()) shl 1)

                if (receivedCount % 50 == 0) {
                    Rlog.d(TAG, "Received RTP packet #$receivedCount: length=${dgram.length} pt=$pt ft=$ft")
                }

                val payloadOff = 12 + 4 * (dgramBuf[0].toInt() and 0x0f) // RTP header + CSRCs
                if (dgram.length <= payloadOff) continue
                val frames = amrBandwidthEfficientToStorage(dgramBuf, payloadOff, dgram.length - payloadOff)
                if (frames == null) {
                    Rlog.w(TAG, "Dropping malformed AMR payload #$receivedCount length=${dgram.length}")
                    continue
                }
                val ts = ((dgramBuf[4].toLong() and 0xff) shl 24) or ((dgramBuf[5].toLong() and 0xff) shl 16) or
                    ((dgramBuf[6].toLong() and 0xff) shl 8) or (dgramBuf[7].toLong() and 0xff)
                if (expectedTs >= 0 && ((expectedTs - ts) and 0xffffffffL) in 1L..8000L) continue
                val missing = if (expectedTs < 0) 0L else ((ts - expectedTs) and 0xffffffffL) / 160
                val fill = if (missing in 1L..50L) missing.toInt() else 0
                expectedTs = (ts + 160L * frames.size) and 0xffffffffL
                val noData = ((AMR_NO_DATA shl 3) or (1 shl 2)).toByte()
                val data = ByteArray(fill) { noData } + frames.reduce { a, b -> a + b }

                val inBufIndex = decoder.dequeueInputBuffer(-1)
                val inBuf = decoder.getInputBuffer(inBufIndex)!!
                inBuf.clear()
                inBuf.put(data)
                decoder.queueInputBuffer(inBufIndex, 0, data.size, 0, 0)

                var timeoutUs = 20_000L
                while (true) {
                    val outBufIndex = decoder.dequeueOutputBuffer(outBufInfo, timeoutUs)
                    timeoutUs = 0L
                    if (outBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                    if (outBufIndex < 0) break
                    val outBuf = decoder.getOutputBuffer(outBufIndex)!!
                    audioTrack.write(outBuf, outBufInfo.size, AudioTrack.WRITE_BLOCKING)
                    decoder.releaseOutputBuffer(outBufIndex, false)
                }
            }
            audioTrack.stop()
            audioTrack.release()
            decoder.stop()
            decoder.release()
        }
    }

    fun extractDestinationFromContact(contact: String): String {
        val r = Regex(".*<(sip:[^>]*)>.*")
        return r.find(contact)!!.groups[1]!!.value
    }

    val callStopped = AtomicBoolean(false)
    val callStarted = AtomicBoolean(false)
    val updateReceived = AtomicBoolean(false)
    val threadsStarted = AtomicBoolean(false)
    val callGeneration = AtomicInteger(0)

    val prAckWaitLock = Object()
    var prAckWait = mutableSetOf<Int>()

    fun handleCall(request: SipRequest): Int {
        val contentType = request.headers["content-type"]?.get(0)
        if (contentType != "application/sdp") return 404
        callStopped.set(false)
        callStarted.set(false)
        threadsStarted.set(false)
        callGeneration.incrementAndGet()
        val generation = callGeneration.get()
        synchronized(prAckWaitLock) {
            prAckWait.clear()
            prAckWaitLock.notifyAll()
        }

        val caller = incomingCallerIdentity(request.headers["from"]?.firstOrNull(), request.headers["privacy"].orEmpty())
        Rlog.d(TAG, "Incoming caller presentation=${caller.presentation}")
        onIncomingCall?.invoke(Object(), caller.number, mapOf(
            "call-id" to request.headers["call-id"]!![0],
            "caller-presentation" to caller.presentation.name,
        ))

        val sdp =
            request.body
                .toString(Charsets.UTF_8)
                .split("[\r\n]+".toRegex())
                .toList()
        Rlog.d(TAG, "Split SDP into $sdp")

        fun sdpElement(command: String): String? {
            val v = sdp.firstOrNull { it.startsWith("$command=") } ?: return null
            return v.substring(2)
        }
        val sdpConnectionData = sdpElement("c")
        val sdpOrigin = sdpElement("o")
        val sdpSessionName = sdpElement("s")
        val sdpTiming = sdpElement("t")
        val sdpBandwidth = sdpElement("b")
        val sdpMedia = sdpElement("m")

        Rlog.d(TAG, "Got sdpTiming $sdpTiming")

        if (sdpTiming != "0 0") {
            Rlog.d(TAG, "Uh-oh, unknown timing mode")
        }

        val rtpRemote = sdpConnectionData!!.split(" ")[2] // c=IN IP6 xxx
        val rtpRemoteAddr = InetAddress.getByName(rtpRemote)
        val rtpRemotePort = sdpMedia!!.split(" ")[1] // m=audio 30798 RTP/AVP 96 97 98 8 18 101 100 99

        val attributes = sdp.filter { it.startsWith("a=") }.map { it.substring(2) }

        fun lookTrackMatching(
            codec: String,
            additional: String = "",
            notAdditional: String = "",
        ): Pair<Int, String>? {
            val maps = attributes.filter { it.startsWith("rtpmap") && it.contains(codec) }
            val matches =
                maps.map { m ->
                    val track = m.split("[: ]+".toRegex())[1].toInt()
                    val desc = m
                    Pair(track, desc)
                }
            Rlog.d(TAG, "Matching $codec, got $matches")
            val matches2 =
                if (matches.size > 1) {
                    matches.sortedBy { m ->
                        val fmtp = attributes.filter { it.startsWith("fmtp:${m.first}") }[0]
                        Rlog.d(TAG, "Matching $codec, for match $m got fmtp $fmtp")
                        if (fmtp.contains(additional)) {
                            0
                        } else if (notAdditional.isNotEmpty() && !fmtp.contains(notAdditional)) {
                            1
                        } else {
                            2
                        }
                    }
                } else {
                    matches
                }
            Rlog.d(TAG, "Matching2 $codec, got $matches2")
            return matches2.firstOrNull()
        }

        fun trackRequirements(track: Int): String? = attributes.firstOrNull { it.startsWith("fmtp:$track") }

        val hasEarlyMedia = request.headers["p-early-media"]?.isNotEmpty() == true
        val callerSupportsPrecondition =
            (
                request.headers["supported"].orEmpty() +
                    request.headers["require"].orEmpty()
            ).any { it.contains("precondition") }

        val (amrTrack, amrTrackDesc) = lookTrackMatching("AMR/8000")!!

        val (dtmfTrack, dtmfTrackDesc) = lookTrackMatching("telephone-event/8000")!!

        val allTracks = listOf(amrTrack, dtmfTrack)
        val myOrigin = SdpOrigin.next()

        thread(start = false) {
            Thread.sleep(500)
            if (callStopped.get() || callGeneration.get() != generation) return@thread
            val rtpSocket = openMediaSocket()
            rtpSocket.connect(rtpRemoteAddr, rtpRemotePort.toInt())
            Rlog.d(
                TAG,
                "RTP socket created: local=${rtpSocket.localAddress}:${rtpSocket.localPort}, remote=${rtpSocket.inetAddress}:${rtpSocket.port}",
            )

            val local =
                if (socket.gLocalAddr() is Inet6Address) {
                    "[${socket.gLocalAddr().hostAddress}]:${serverSocket.localPort}"
                } else {
                    "${socket.gLocalAddr().hostAddress}:${serverSocket.localPort}"
                }
            val contactTel = dialogContact(local, "tcp")
            val mySeqCounter = reliableSequenceCounter++
            val ipType = if (socket.gLocalAddr() is Inet6Address) "IP6" else "IP4"
            val preconditionLines =
                if (callerSupportsPrecondition) {
                    listOf(
                        "a=curr:qos local none",
                        "a=curr:qos remote none",
                        "a=des:qos mandatory local sendrecv",
                        "a=des:qos mandatory remote sendrecv",
                        "a=conf:qos remote sendrecv",
                    )
                } else {
                    emptyList()
                }

            val mySdp =
                (
                    listOf(
                        "v=0",
                        myOrigin.line(ipType, socket.gLocalAddr().hostAddress),
                        "s=phh voice call",
                        "c=IN $ipType ${socket.gLocalAddr().hostAddress}",
                        "b=AS:38",
                        "b=RS:475",
                        "b=RR:1425",
                        "t=0 0",
                        "m=audio ${rtpSocket.localPort} RTP/AVP ${allTracks.joinToString(" ")}",
                        "b=AS:38",
                        "b=RS:475",
                        "b=RR:1425",
                        "a=$amrTrackDesc",
                        "a=ptime:20",
                        "a=maxptime:240",
                        "a=$dtmfTrackDesc",
                        "a=fmtp:$dtmfTrack 0-15",
                    ) + preconditionLines + listOf("a=sendrecv")
                ).toSdpBody().let(::labSdp)

            val localToTag = randomBytes(6).toHex()
            val toWithTag =
                request.headers["to"]!!.map { h ->
                    if (h.contains(";tag=")) h else "$h;tag=$localToTag"
                }

            val myHeaders =
                commonHeaders + // Require: precondition
                    """
                        Contact: $contactTel
                        Allow: INVITE, ACK, CANCEL, BYE, UPDATE, REFER, NOTIFY, INFO, MESSAGE, PRACK, OPTIONS
                        Content-Type: application/sdp
                        Require: 100rel${if (callerSupportsPrecondition) ", precondition" else ""}
                        RSeq: $mySeqCounter
                            """.toSipHeadersMap() +
                    request.headers.filter { (k, _) -> k in listOf("cseq", "via", "from", "to", "call-id") } +
                    mapOf("to" to toWithTag) -
                    "route" - "security-verify"

            if (hasEarlyMedia) synchronized(prAckWaitLock) { prAckWait += mySeqCounter }
            currentCall =
                Call(
                    outgoing = false,
                    amrTrack = amrTrack,
                    amrTrackDesc = amrTrackDesc,
                    dtmfTrack = dtmfTrack,
                    dtmfTrackDesc = dtmfTrackDesc,
                    callHeaders = myHeaders - "require" - "content-type" + "Supported: 100rel, replaces, timer".toSipHeadersMap(),
                    rtpRemoteAddr = rtpRemoteAddr,
                    rtpRemotePort = rtpRemotePort.toInt(),
                    rtpSocket = rtpSocket,
                    sdp = mySdp,
                    sdpOrigin = myOrigin,
                    hasEarlyMedia = hasEarlyMedia,
                    remoteContact = extractDestinationFromContact(request.headers["contact"]!![0]),
                )

            if (threadsStarted.compareAndSet(false, true)) {
                callDecodeThread()
                callEncodeThread()
            }

            if (hasEarlyMedia) {
                val msg =
                    SipResponse(
                        statusCode = 183,
                        statusString = "Session Progress",
                        headersParam = myHeaders,
                        body = mySdp,
                    )
                Rlog.d(TAG, "Sending $msg")
                synchronized(socket.gWriter()) { socket.gWriter().write(msg.toByteArray()) }
                if (!waitPrack(mySeqCounter, generation)) return@thread
            }
            if (!hasEarlyMedia) {
                val myHeaders2 =
                    myHeaders - "rseq" - "content-type" - "require" +
                        """
Supported: 100rel, replaces, timer
P-Access-Network-Info: ${buildPAccessNetworkInfo()}

""".toSipHeadersMap()
                val msg2 =
                    SipResponse(
                        statusCode = 180,
                        statusString = "Ringing",
                        headersParam = myHeaders2,
                    )
                Rlog.d(TAG, "Sending $msg2")
                synchronized(socket.gWriter()) { socket.gWriter().write(msg2.toByteArray()) }
            }
        }.apply {
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e ->
                Rlog.w(TAG, "Incoming call setup failed; ending the call", e)
                callStopped.set(true)
                closeMediaSockets()
                onCancelledCall?.invoke(Object(), "", emptyMap())
            }
            start()
        }

        if (!hasEarlyMedia) {
            return 0
        }
        return 100
    }

    fun handleSms(request: SipRequest): Int {
        val sms = request.body.SipSmsDecode()
        if (sms == null) {
            Rlog.w(TAG, "Could not decode sms pdu")
            return 500
        }
        Rlog.d(TAG, "Decoded SMS type ${sms.type}, ${sms.pdu?.toString()}")
        when (sms.type) {
            SmsType.RP_DATA_FROM_NETWORK -> {
                val receivedCb = onSmsReceived
                if (receivedCb == null) {
                    Rlog.d(TAG, "No onSmsReceived callback!")
                    return 500
                }

                val token = smsLock.withLock { smsToken++ }
                val dest =
                    request.headers["from"]!![0]
                        .getParams()
                        .component1()
                        .trimStart('<')
                        .trimEnd('>')
                val callId = request.headers["call-id"]!![0]
                val cseq = request.headers["cseq"]!![0]
                smsHeadersMap[token] = smsHeaders(dest, callId, cseq)
                try {
                    receivedCb(token, "3gpp", sms.pdu!!)
                } catch (t: Throwable) {
                    Rlog.d(TAG, "Failed sending SMS to framework", t)
                }
            }

            SmsType.RP_ACK_FROM_NETWORK -> {
                try {
                    onSmsStatusReportReceived?.invoke(sms.ref.toInt(), "3gpp", ByteArray(2))
                } catch (t: Throwable) {
                    Rlog.d(TAG, "Failed sending SMS ACK to framework", t)
                }
            }

            SmsType.RP_ERROR_FROM_NETWORK -> {
                Rlog.d(TAG, "SMS error from network")
            }

            else -> {
                return 500
            }
        }
        return 200
    }

    fun sendSms(
        smsSmsc: String?,
        pdu: ByteArray,
        ref: Int,
        successCb: (() -> Unit),
        failCb: (() -> Unit),
    ) {
        val decodableSmsc =
            try {
                PhoneNumberUtils.numberToCalledPartyBCD(smsSmsc, PhoneNumberUtils.BCD_EXTENDED_TYPE_CALLED_PARTY)
                true
            } catch (t: Throwable) {
                false
            }

        val smsManager = SmsManager.getSmsManagerForSubscriptionId(subId)
        val smscIdentity =
            try {
                val i =
                    smsManager
                        .javaClass
                        .getMethod("getSmscIdentity")
                        .invoke(smsManager) as Uri
                if (i.host == null) null else i
            } catch (t: Throwable) {
                null
            }
        Rlog.d(TAG, "Got smscIdentity $smscIdentity")
        val smsc =
            if (smsSmsc != null && decodableSmsc) {
                smsSmsc
            } else if (forceSmsc != null) {
                forceSmsc
            } else {
                try {
                    Rlog.d(
                        TAG,
                        "Got smsc $smscIdentity // host is ${smscIdentity?.host} // ${smscIdentity?.scheme} // ${smscIdentity?.path}",
                    )
                    smscIdentity!!.host!!
                } catch (t: Throwable) {
                    try {
                        Rlog.d(TAG, "getSmscIdentity failed", t)
                        val smscStr = smsManager.smscAddress
                        val smscMatchRegex = Regex("([0-9]+)")
                        Rlog.d(TAG, "Got smsc $smscStr, match ${smscMatchRegex.find(smscStr!!)}")
                        val match = smscMatchRegex.find(smscStr!!)!!
                        match.groupValues[1]
                    } catch (t: Throwable) {
                        Rlog.d(TAG, "smscAddress failed", t)
                        null
                    }
                }
            }

        val data = SipSmsEncodeSms(ref.toByte(), if (smsc == null) "" else "+$smsc", pdu)
        Rlog.d(TAG, "sending sms ${data.toHex()} to smsc $smsc")
        val dest =
            if (smscIdentity != null) {
                "sip:$smscIdentity"
            } else {
                "sip:+$smsc@$realm"
            }

        val msg =
            SipRequest(
                SipMethod.MESSAGE,
                "sip:${smscIdentity ?: realm}",
                commonHeaders +
                    """
                    From: <$mySip>
                    To: <$dest>
                    P-Preferred-Identity: <$mySip>
                    P-Asserted-Identity: <$mySip>
                    Expires: 600000
                    Content-Type: application/vnd.3gpp.sms
                    Supported: sec-agree, path
                    Require: sec-agree
                    Proxy-Require: sec-agree
                    Allow: MESSAGE
                    Accept-Contact: *;+g.3gpp.smsip;require;explicit
                    Request-Disposition: no-fork
                    """.toSipHeadersMap(),
                data,
            )
        setResponseCallback(
            msg.headers["call-id"]!![0],
            { resp: SipResponse ->
                if (resp.statusCode == 200 || resp.statusCode == 202) {
                    successCb()
                } else {
                    failCb()
                }
                true
            },
        )
        Rlog.d(TAG, "Sending $msg")
        synchronized(socket.gWriter()) { socket.gWriter().write(msg.toByteArray()) }
    }

    fun sendSmsAck(
        token: Int,
        ref: Int,
        error: Boolean,
    ) {
        Rlog.d(TAG, "sending sms ack")
        val body = SipSmsEncodeAck(ref.toByte())
        val headers = smsHeadersMap.remove(token)
        if (headers == null) {
            return
        }
        if (error) {
            return
        }
        val msg =
            SipRequest(
                SipMethod.MESSAGE,
                headers.dest,
                commonHeaders +
                    """
                    Cseq: ${headers.cseq}
                    In-Reply-To: ${headers.callId}
                    Content-Type: application/vnd.3gpp.sms
                    Proxy-Require: sec-agree
                    Require: sec-agree
                    Allow: MESSAGE
                    Supported: path, gruu, sec-agree
                    Request-Disposition: no-fork
                    Accept-Contact: *;+g.3gpp.smsip
                    """.toSipHeadersMap(),
                body,
            )
        setResponseCallback(msg.headers["call-id"]!![0], { true })
        Rlog.d(TAG, "Sending $msg")
        synchronized(socket.gWriter()) { socket.gWriter().write(msg.toByteArray()) }
    }
}
