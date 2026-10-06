package me.phh.sip

import android.net.IpSecManager
import android.net.IpSecTransform
import android.net.Network
import android.telephony.Rlog
import java.io.FileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.StandardProtocolFamily
import java.nio.ByteBuffer
import java.nio.channels.Channel
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.spi.SelectorProvider

private const val TCP_MAXSEG = 2 // linux/tcp.h; not exposed by OsConstants

private val sipTcpMss: Int by lazy {
    android.os.SystemProperties.getInt("debug.phh.ims.tcp_mss", 1000)
}

internal fun clampSipTcpMss(fd: FileDescriptor, what: String) {
    val mss = sipTcpMss
    if (mss <= 0) return
    try {
        android.system.Os.setsockoptInt(fd, android.system.OsConstants.IPPROTO_TCP, TCP_MAXSEG, mss)
        Rlog.d("PHH SipConnection", "$what: TCP MSS clamped to $mss")
    } catch (e: Exception) {
        Rlog.w("PHH SipConnection", "$what: could not clamp TCP MSS to $mss", e)
    }
}

interface SipConnection {
    fun close()

    fun enableIpsec(
        ipSecBuilder: IpSecTransform.Builder,
        ipSecManager: IpSecManager,
        clientSpiC: IpSecManager.SecurityParameterIndex,
        serverSpiS: IpSecManager.SecurityParameterIndex,
    )

    fun gLocalAddr(): InetAddress

    fun connect(remotePort: Int)

    fun gWriter(): OutputStream

    fun gReader(): SipReader

    fun gLocalPort(): Int

    fun getChannel(): SelectableChannel
}

class SipConnectionTcp(
    val network: Network,
    val remoteAddr: InetAddress,
    val _localAddr: InetAddress? = null,
    val _localPort: Int = 0,
) : SipConnection {
    val socket: Socket

    var localAddr: InetAddress
    var localPort: Int
    var remotePort: Int = 0
    lateinit var writer: OutputStream
    lateinit var reader: SipReader

    lateinit var inTransform: IpSecTransform
    lateinit var outTransform: IpSecTransform
    var connected = false

    init {
        socket = network.socketFactory.createSocket()
        clampSipTcpMss(
            socket.javaClass.getMethod("getFileDescriptor\$").invoke(socket) as FileDescriptor,
            "client socket")
        if (_localAddr != null) {
            socket.bind(InetSocketAddress(_localAddr, _localPort))
        }
        localAddr = socket.localAddress
        localPort = socket.localPort
    }

    override fun connect(_remotePort: Int) {
        remotePort = _remotePort
        socket.connect(InetSocketAddress(remoteAddr, remotePort), 15000)
        if (_localAddr == null) {
            localAddr = socket.localAddress
            localPort = socket.localPort
        }
        writer = socket.getOutputStream()
        reader = socket.getInputStream().sipReader()
        connected = true
    }

    override fun gWriter(): OutputStream =
        if (this::writer.isInitialized) writer else throw java.io.IOException("SIP connection not established yet")

    override fun gReader(): SipReader = reader

    override fun gLocalPort(): Int = localPort

    override fun getChannel(): SelectableChannel = socket.channel

    override fun close() {
        socket.close()
        if (this::inTransform.isInitialized) inTransform.close()
        if (this::outTransform.isInitialized) outTransform.close()
    }

    override fun enableIpsec(
        ipSecBuilder: IpSecTransform.Builder,
        ipSecManager: IpSecManager,
        clientSpiC: IpSecManager.SecurityParameterIndex,
        serverSpiS: IpSecManager.SecurityParameterIndex,
    ) {
        check(!connected)
        inTransform = ipSecBuilder.buildTransportModeTransform(remoteAddr, clientSpiC)
        ipSecManager.applyTransportModeTransform(socket, IpSecManager.DIRECTION_IN, inTransform)
        outTransform = ipSecBuilder.buildTransportModeTransform(localAddr, serverSpiS)
        ipSecManager.applyTransportModeTransform(socket, IpSecManager.DIRECTION_OUT, outTransform)
    }

    override fun gLocalAddr(): InetAddress = localAddr
}

class SipConnectionTcpServer(
    val network: Network,
    val remoteAddr: InetAddress,
    val localAddr: InetAddress,
    val localPort: Int,
) {
    val serverSocket: ServerSocket
    val serverSocketFd: FileDescriptor
    lateinit var inTransform: IpSecTransform
    lateinit var outTransform: IpSecTransform

    init {
        serverSocket = ServerSocket()
        serverSocket.bind(InetSocketAddress(localAddr, localPort))
        serverSocketFd =
            serverSocket.javaClass.getMethod("getFileDescriptor\$").invoke(serverSocket)
                as FileDescriptor
        network.bindSocket(serverSocketFd)
        clampSipTcpMss(serverSocketFd, "server socket")
    }

    fun accept(): Pair<SipReader, OutputStream> {
        val client = serverSocket.accept()
        return Pair(client.getInputStream().sipReader(), client.getOutputStream())
    }

    fun enableIpsec(
        ipSecManager: IpSecManager,
        inTransform: IpSecTransform,
        outTransform: IpSecTransform,
    ) {
        this.inTransform = inTransform
        ipSecManager.applyTransportModeTransform(
            serverSocketFd,
            IpSecManager.DIRECTION_IN,
            inTransform,
        )
        this.outTransform = outTransform
        ipSecManager.applyTransportModeTransform(
            serverSocketFd,
            IpSecManager.DIRECTION_OUT,
            outTransform,
        )
    }

    fun close() {
        serverSocket.close()
        if (this::inTransform.isInitialized) inTransform.close()
        if (this::outTransform.isInitialized) outTransform.close()
    }

    fun getChannel(): SelectableChannel = serverSocket.channel
}

class SipConnectionUdp(
    val network: Network,
    val remoteAddr: InetAddress,
    val _localAddr: InetAddress? = null,
    val _localPort: Int = 0,
) : SipConnection {
    val socket: DatagramSocket

    var localAddr: InetAddress
    var localPort: Int
    var remotePort: Int = 0
    lateinit var writer: OutputStream
    lateinit var reader: SipReader

    lateinit var inTransform: IpSecTransform
    lateinit var outTransform: IpSecTransform
    var connected = false

    init {
        val channel = DatagramChannel.open(if (remoteAddr is Inet6Address) StandardProtocolFamily.INET6 else StandardProtocolFamily.INET)
        if (_localAddr != null) {
            channel.bind(InetSocketAddress(_localAddr, _localPort))
        }
        socket = channel.socket()
        network.bindSocket(socket)

        localAddr = socket.localAddress
        localPort = socket.localPort
    }

    override fun connect(_remotePort: Int) {
        remotePort = _remotePort
        if (_localAddr == null) {
            localAddr = socket.localAddress
            localPort = socket.localPort
        }
        writer =
            object : OutputStream() {
                override fun write(p0: Int) {
                    write(byteArrayOf(p0.toByte()))
                }

                override fun write(p0: ByteArray) {
                    socket.channel.send(ByteBuffer.wrap(p0), InetSocketAddress(remoteAddr, remotePort))
                }
            }
        reader =
            object : InputStream() {
                val currentDgram = DatagramPacket(ByteArray(128 * 1024), 128 * 1024)
                var currentPosition = 0
                var currentSize = 0

                fun recvPacket() {
                    select(listOf(getChannel()))
                    socket.receive(currentDgram)
                    currentPosition = 0
                    currentSize = currentDgram.length
                }

                override fun read(): Int {
                    if (currentPosition >= currentSize) {
                        recvPacket()
                    }
                    val ret = currentDgram.data[currentPosition++].toInt()
                    return ret
                }

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    if (currentPosition >= currentSize) {
                        recvPacket()
                    }
                    val toRead = minOf(len, currentSize - currentPosition)
                    currentDgram.data.copyInto(b, off, currentPosition, currentPosition + toRead)
                    currentPosition += toRead
                    return toRead
                }
            }.sipReader()
        connected = true
    }

    override fun gWriter(): OutputStream = writer

    override fun gReader(): SipReader = reader

    override fun gLocalPort(): Int = localPort

    override fun getChannel(): SelectableChannel = socket.channel

    override fun close() {
        socket.close()
    }

    override fun enableIpsec(
        ipSecBuilder: IpSecTransform.Builder,
        ipSecManager: IpSecManager,
        clientSpiC: IpSecManager.SecurityParameterIndex,
        serverSpiS: IpSecManager.SecurityParameterIndex,
    ) {
        check(!connected)
        inTransform = ipSecBuilder.buildTransportModeTransform(remoteAddr, clientSpiC)
        ipSecManager.applyTransportModeTransform(socket, IpSecManager.DIRECTION_IN, inTransform)
        outTransform = ipSecBuilder.buildTransportModeTransform(localAddr, serverSpiS)
        ipSecManager.applyTransportModeTransform(socket, IpSecManager.DIRECTION_OUT, outTransform)
    }

    override fun gLocalAddr(): InetAddress = localAddr
}

class SipConnectionUdpServer(
    val network: Network,
    val remoteAddr: InetAddress,
    val localAddr: InetAddress,
    val localPort: Int,
) {
    val socket: DatagramSocket
    val socketFd: FileDescriptor
    lateinit var inTransform: IpSecTransform
    lateinit var outTransform: IpSecTransform

    init {
        val channel = DatagramChannel.open(if (remoteAddr is Inet6Address) StandardProtocolFamily.INET6 else StandardProtocolFamily.INET)
        channel.bind(InetSocketAddress(localAddr, localPort))
        socket = channel.socket()
        network.bindSocket(socket)
        socketFd =
            socket.javaClass.getMethod("getFileDescriptor\$").invoke(socket)
                as FileDescriptor
    }

    fun gReader(): SipReader {
        return object : InputStream() {
            val currentDgram = DatagramPacket(ByteArray(128 * 1024), 128 * 1024)
            var currentPosition = 0
            var currentSize = 0

            fun recvPacket() {
                select(listOf(getChannel()))
                socket.receive(currentDgram)
                currentPosition = 0
                currentSize = currentDgram.length
            }

            override fun read(): Int {
                if (currentPosition >= currentSize) {
                    recvPacket()
                }
                val ret = currentDgram.data[currentPosition++].toInt()
                return ret
            }

            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int {
                if (currentPosition >= currentSize) {
                    recvPacket()
                }
                val toRead = minOf(len, currentSize - currentPosition)
                currentDgram.data.copyInto(b, off, currentPosition, currentPosition + toRead)
                currentPosition += toRead
                return toRead
            }
        }.sipReader()
    }

    fun enableIpsec(
        ipSecManager: IpSecManager,
        inTransform: IpSecTransform,
        outTransform: IpSecTransform,
    ) {
        this.inTransform = inTransform
        ipSecManager.applyTransportModeTransform(
            socketFd,
            IpSecManager.DIRECTION_IN,
            inTransform,
        )
        this.outTransform = outTransform
        ipSecManager.applyTransportModeTransform(
            socketFd,
            IpSecManager.DIRECTION_OUT,
            outTransform,
        )
    }

    fun getChannel(): SelectableChannel = socket.channel
}

fun select(channels: List<SelectableChannel>): Int {
    var returnValue = -1
    Selector.open().use { selector ->
        for (channel in channels) {
            channel.configureBlocking(false)
            channel.register(selector, SelectionKey.OP_READ)
        }

        val nSelectedKeys = selector.select()
        for (key in selector.selectedKeys()) {
            if (key.isReadable) {
                val index = channels.indexOf(key.channel())
                if (index != -1) {
                    Rlog.e("PHH", "When selecting got result $index")
                    returnValue = index
                    break
                }
            }
        }
    }
    for (channel in channels) {
        channel.configureBlocking(true)
    }

    return returnValue
}
