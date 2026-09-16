package com.singular.cast.net

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class DiscoveredPc(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
) {
    val label: String get() = "$name ($host)"
}

/**
 * UDP broadcast discovery of Singular Desk instances.
 *
 * A single broadcast to 255.255.255.255 misses PCs on networks where the
 * general broadcast address is filtered, so every interface's own directed
 * broadcast address is probed too.
 */
object Discovery {

    private const val TAG = "SingularDiscovery"
    private const val LISTEN_WINDOW_MS = 1_500
    private const val SOCKET_TIMEOUT_MS = 250

    suspend fun scan(): List<DiscoveredPc> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, DiscoveredPc>()
        val probe = Protocol.DISCOVERY_PROBE.toByteArray(Charsets.UTF_8)

        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = SOCKET_TIMEOUT_MS

            for (address in broadcastAddresses()) {
                try {
                    socket.send(DatagramPacket(probe, probe.size, address, Protocol.UDP_PORT))
                } catch (e: Exception) {
                    Log.d(TAG, "probe to $address failed: ${e.message}")
                }
            }

            val deadline = System.currentTimeMillis() + LISTEN_WINDOW_MS
            val buffer = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    Log.d(TAG, "receive failed: ${e.message}")
                    break
                }
                parseReply(packet)?.let { found[it.id] = it }
            }
        }
        found.values.toList()
    }

    private fun parseReply(packet: DatagramPacket): DiscoveredPc? {
        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        return try {
            val json = JSONObject(text)
            if (json.optString("t") != "singular.pc") return null
            DiscoveredPc(
                id = json.optString("id", packet.address.hostAddress ?: text),
                name = json.optString("name", "PC"),
                // Trust the socket's source address, not a name in the payload.
                host = packet.address.hostAddress ?: return null,
                port = json.optInt("port", Protocol.TCP_PORT),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        runCatching { addresses.add(InetAddress.getByName("255.255.255.255")) }

        runCatching {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                for (ia in nic.interfaceAddresses) {
                    ia.broadcast?.let { addresses.add(it) }
                }
            }
        }
        return addresses.distinct()
    }

    /** Validate a manually typed "host" or "host:port" entry. */
    fun parseManual(text: String): InetSocketAddress? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val (host, portText) = if (trimmed.count { it == ':' } == 1) {
            trimmed.substringBefore(':') to trimmed.substringAfter(':')
        } else {
            trimmed to Protocol.TCP_PORT.toString()
        }
        val port = portText.toIntOrNull() ?: return null
        if (port !in 1..65535 || host.isEmpty()) return null
        return InetSocketAddress.createUnresolved(host, port)
    }
}
