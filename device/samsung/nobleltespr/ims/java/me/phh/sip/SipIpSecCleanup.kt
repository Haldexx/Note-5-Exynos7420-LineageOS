package me.phh.sip

import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Binder cleanup must not delay closing signalling sockets on bearer loss. */
internal class SipResourceCloser(private val executor: Executor) {
    fun release(resource: AutoCloseable) {
        executor.execute { runCatching { resource.close() } }
    }
}

internal object SipIpSecCleanup {
    // Process lifetime: do not shut down and lose resources during reconnect.
    private val closer = SipResourceCloser(Executors.newSingleThreadExecutor { task ->
        Thread(task, "IMS-IPsec-cleanup").apply { isDaemon = true }
    })

    fun release(resource: AutoCloseable) = closer.release(resource)
}
