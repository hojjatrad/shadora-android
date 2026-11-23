package com.example.vpnservice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Measures real TCP handshake latency (RTT in milliseconds) against server endpoints.
 */
class ServerLatencyTester {

    suspend fun measureTcpLatencyMs(host: String, port: Int, timeoutMs: Int = 3500): Int =
        withContext(Dispatchers.IO) {
            if (host.isBlank() || port !in 1..65535) return@withContext -1
            val socket = Socket()
            val start = System.nanoTime()
            try {
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                val elapsedMs = ((System.nanoTime() - start) / 1_000_000L).toInt()
                elapsedMs.coerceAtLeast(1)
            } catch (_: Throwable) {
                -1
            } finally {
                runCatching { socket.close() }
            }
        }

    suspend fun measureBatch(
        targets: List<Triple<Long, String, Int>>,
        concurrency: Int = 6,
        onSingleResult: suspend (serverId: Long, latencyMs: Int) -> Unit
    ) = coroutineScope {
        val semaphore = Semaphore(concurrency)
        targets.map { (id, host, port) ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val rtt = measureTcpLatencyMs(host, port)
                    onSingleResult(id, rtt)
                }
            }
        }.awaitAll()
    }
}
