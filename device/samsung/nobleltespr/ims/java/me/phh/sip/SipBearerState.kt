package me.phh.sip

import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress

/** A bearer revision outlives individual proxy attempts, but never a network loss. */
internal class SipBearerState<T> {
    data class Snapshot<T>(val network: T, val revision: Long)
    private var network: T? = null
    private var closed = false
    private var revision = 0L
    private var attempt: SipBearerAttempt? = null

    fun available(value: T): Boolean {
        val old = synchronized(this) {
            if (closed || network == value) return false
            network = value
            revision++
            attempt.also { attempt = null }
        }
        old?.close()
        return true
    }

    fun lost(value: T): Boolean {
        val old = synchronized(this) {
            if (network != value) return false
            network = null
            revision++
            attempt.also { attempt = null }
        }
        // Closing the socket interrupts connect/read without taking the registration lock.
        old?.close()
        return true
    }

    fun close() {
        val old = synchronized(this) {
            if (closed) return
            closed = true
            network = null
            revision++
            attempt.also { attempt = null }
        }
        old?.close()
    }

    @Synchronized fun snapshot(): Snapshot<T>? = network?.let { Snapshot(it, revision) }
    @Synchronized fun isCurrent(value: Snapshot<T>): Boolean =
        !closed && network == value.network && revision == value.revision

    fun begin(value: Snapshot<T>): SipBearerAttempt {
        val next = SipBearerAttempt()
        val old = synchronized(this) {
            if (!isCurrent(value)) throw IOException("IMS bearer changed before registration")
            attempt.also { attempt = next }
        }
        old?.close()
        return next
    }

    @Synchronized fun ifCurrent(value: Snapshot<T>, action: () -> Unit): Boolean {
        if (!isCurrent(value)) return false
        action()
        return true
    }
}

/** Resources created after cancellation are closed immediately, including partial setup. */
internal class SipBearerAttempt {
    private var closed = false
    private val resources = mutableListOf<() -> Unit>()

    fun <T> keep(value: T, release: (T) -> Unit): T {
        synchronized(this) {
            if (!closed) {
                resources.add { release(value) }
                return value
            }
        }
        runCatching { release(value) }
        throw IOException("IMS bearer changed during registration")
    }

    @Synchronized fun checkOpen() {
        if (closed) throw IOException("IMS bearer changed during registration")
    }

    fun close() {
        val pending = synchronized(this) {
            if (closed) return
            closed = true
            resources.toList().also { resources.clear() }
        }
        pending.forEach { runCatching { it() } }
    }
}

internal fun sipLocalAddress(addresses: List<InetAddress>, remote: InetAddress): InetAddress =
    addresses.firstOrNull {
        (it is Inet6Address) == (remote is Inet6Address) &&
            !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress
    } ?: throw IOException("No IMS source address matching the P-CSCF address family")
