package com.mohammask.lanpower.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.mohammask.lanpower.model.Device
import com.mohammask.lanpower.model.DiscoveredDevice
import com.mohammask.lanpower.model.NetworkValidation
import com.mohammask.lanpower.model.PowerAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

sealed interface StatusResult {
    data class Online(
        val hostname: String,
        val mac: String,
        val networkInterface: String,
        val authRequired: Boolean,
        val version: String,
    ) : StatusResult

    data class Offline(val reason: String? = null) : StatusResult
}

sealed interface VerifyResult {
    data object Authorized : VerifyResult
    data object Unauthorized : VerifyResult
    data class RateLimited(val retryAfterSeconds: Int) : VerifyResult
    data class Error(val message: String) : VerifyResult
}

sealed interface CommandResult {
    data object Success : CommandResult
    data object Unauthorized : CommandResult
    data class RateLimited(val retryAfterSeconds: Int) : CommandResult
    data class Error(val message: String) : CommandResult
}

data class LocalSubnetInfo(
    val localIp: String,
    val prefixLength: Int,
    val broadcastIp: String,
    val candidateIps: List<String>,
)

class LanPowerClient {

    companion object {
        private const val EXPECTED_SERVICE = "lan-power-agent"
        private const val WOL_PORT = 9
        private const val WOL_PACKET_COUNT = 3
        private const val WOL_PACKET_DELAY_MS = 100L
        private const val FALLBACK_BROADCAST_IP = "255.255.255.255"
        private const val SCAN_CONCURRENCY = 40
        private const val SCAN_TIMEOUT_MS = 350
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val DEFAULT_RETRY_AFTER_SECONDS = 60
        const val STATUS_TIMEOUT_MS = 1500
    }

    suspend fun getStatus(
        ip: String,
        port: Int,
        timeoutMs: Int = STATUS_TIMEOUT_MS,
    ): StatusResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("http://${ip.trim()}:$port/status")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                useCaches = false
                setRequestProperty("Accept", "application/json")
            }

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return@withContext StatusResult.Offline("HTTP $code")
            }

            val body = readResponseBody(connection)
            val json = JSONObject(body)
            if (json.optString("service") != EXPECTED_SERVICE) {
                return@withContext StatusResult.Offline("Not a LanPower agent")
            }

            StatusResult.Online(
                hostname = json.optString("hostname", ip.trim()).ifBlank { ip.trim() },
                mac = NetworkValidation.normalizeMac(json.optString("mac", "")),
                networkInterface = json.optString("interface", ""),
                authRequired = json.optBoolean("auth_required", false),
                version = json.optString("version", "1.0"),
            )
        } catch (e: Exception) {
            StatusResult.Offline(e.message)
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun verifyPin(
        ip: String,
        port: Int,
        token: String,
        timeoutMs: Int = 2000,
    ): VerifyResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("http://${ip.trim()}:$port/verify")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                useCaches = false
                doOutput = true
                setFixedLengthStreamingMode(0)
                setRequestProperty("Accept", "application/json")
                if (token.isNotBlank()) {
                    setRequestProperty("X-Auth-Token", token)
                }
            }
            connection.outputStream.use { /* Empty POST body */ }

            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val json = JSONObject(readResponseBody(connection))
                    if (json.optBoolean("ok", false) && json.optBoolean("authorized", false)) {
                        VerifyResult.Authorized
                    } else {
                        VerifyResult.Unauthorized
                    }
                }
                HttpURLConnection.HTTP_UNAUTHORIZED -> VerifyResult.Unauthorized
                HTTP_TOO_MANY_REQUESTS -> VerifyResult.RateLimited(parseRetryAfter(connection))
                else -> VerifyResult.Error("Agent returned HTTP $code")
            }
        } catch (e: Exception) {
            VerifyResult.Error(e.message ?: "Unable to reach agent")
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun sleep(
        device: Device,
        timeoutMs: Int = 3000,
    ): CommandResult = sendPowerCommand(
        ip = device.ip,
        port = device.port,
        action = PowerAction.Sleep,
        token = device.authToken,
        timeoutMs = timeoutMs,
    )

    suspend fun sendPowerCommand(
        ip: String,
        port: Int,
        action: PowerAction,
        token: String,
        timeoutMs: Int = 3000,
    ): CommandResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("http://${ip.trim()}:$port/${action.endpoint}")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                useCaches = false
                doOutput = true
                setFixedLengthStreamingMode(0)
                setRequestProperty("Accept", "application/json")
                if (token.isNotBlank()) {
                    setRequestProperty("X-Auth-Token", token)
                }
            }
            connection.outputStream.use { /* Empty POST body */ }

            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val json = JSONObject(readResponseBody(connection))
                    if (json.optBoolean("ok", false)) {
                        CommandResult.Success
                    } else {
                        CommandResult.Error(json.optString("error", "Command rejected"))
                    }
                }
                HttpURLConnection.HTTP_UNAUTHORIZED -> CommandResult.Unauthorized
                HttpURLConnection.HTTP_NOT_FOUND -> {
                    if (action == PowerAction.Sleep) {
                        CommandResult.Error(
                            "Sleep is not supported by this agent version. Please update your LanPower script.",
                        )
                    } else {
                        CommandResult.Error("Agent returned HTTP $code")
                    }
                }
                HTTP_TOO_MANY_REQUESTS -> CommandResult.RateLimited(parseRetryAfter(connection))
                else -> CommandResult.Error("Agent returned HTTP $code")
            }
        } catch (e: Exception) {
            CommandResult.Error(e.message ?: "Unable to reach agent")
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun sendWakeOnLan(
        mac: String,
        broadcastIp: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val magicPacket = buildMagicPacket(mac)
            val targets = buildList {
                val primary = broadcastIp.trim()
                if (NetworkValidation.isValidIpv4(primary)) {
                    add(InetAddress.getByName(primary))
                }
                if (primary != FALLBACK_BROADCAST_IP) {
                    add(InetAddress.getByName(FALLBACK_BROADCAST_IP))
                }
            }
            require(targets.isNotEmpty()) { "No valid broadcast address available" }

            DatagramSocket().use { socket ->
                socket.broadcast = true
                repeat(WOL_PACKET_COUNT) { attempt ->
                    for (address in targets) {
                        val packet = DatagramPacket(magicPacket, magicPacket.size, address, WOL_PORT)
                        socket.send(packet)
                    }
                    if (attempt < WOL_PACKET_COUNT - 1) {
                        delay(WOL_PACKET_DELAY_MS)
                    }
                }
            }
        }
    }

    fun buildMagicPacket(mac: String): ByteArray {
        val normalized = NetworkValidation.normalizeMac(mac)
        require(NetworkValidation.isValidMac(normalized)) { "Invalid MAC address: $mac" }
        val macBytes = normalized.split(':').map { it.toInt(16).toByte() }.toByteArray()
        return ByteArray(6 + 16 * 6).apply {
            for (i in 0 until 6) {
                this[i] = 0xFF.toByte()
            }
            for (repeatIndex in 0 until 16) {
                System.arraycopy(macBytes, 0, this, 6 + repeatIndex * 6, 6)
            }
        }
    }

    fun detectLocalSubnet(context: Context): LocalSubnetInfo? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        val isLocalLan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!isLocalLan) return null

        val linkProperties = cm.getLinkProperties(network) ?: return null
        val ipv4Link = linkProperties.linkAddresses.firstOrNull { linkAddr ->
            val addr = linkAddr.address
            addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress
        } ?: return null

        val localIp = ipv4Link.address.hostAddress ?: return null
        val prefixLength = ipv4Link.prefixLength.takeIf { it in 8..30 } ?: 24
        val broadcastIp = NetworkValidation.computeBroadcastIp(localIp, prefixLength)
        val octets = localIp.split('.')
        if (octets.size != 4) return null
        val subnetPrefix = "${octets[0]}.${octets[1]}.${octets[2]}"
        val candidates = (1..254).map { host -> "$subnetPrefix.$host" }

        return LocalSubnetInfo(
            localIp = localIp,
            prefixLength = prefixLength,
            broadcastIp = broadcastIp,
            candidateIps = candidates,
        )
    }

    suspend fun scanLan(
        context: Context,
        port: Int,
        onProgress: (scanned: Int, total: Int) -> Unit,
        onDeviceFound: (DiscoveredDevice) -> Unit,
    ): LocalSubnetInfo? = coroutineScope {
        val subnet = detectLocalSubnet(context) ?: return@coroutineScope null
        val total = subnet.candidateIps.size
        val scanned = AtomicInteger(0)
        val semaphore = Semaphore(SCAN_CONCURRENCY)

        onProgress(0, total)

        subnet.candidateIps.map { candidateIp ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val result = getStatus(candidateIp, port, SCAN_TIMEOUT_MS)
                    if (result is StatusResult.Online) {
                        val device = DiscoveredDevice(
                            hostname = result.hostname,
                            ip = candidateIp,
                            port = port,
                            mac = result.mac,
                            broadcastIp = NetworkValidation.computeBroadcastIp(
                                candidateIp,
                                subnet.prefixLength,
                            ).ifBlank { subnet.broadcastIp },
                            networkInterface = result.networkInterface,
                            authRequired = result.authRequired,
                            version = result.version,
                        )
                        onDeviceFound(device)
                    }
                    val count = scanned.incrementAndGet()
                    onProgress(count, total)
                }
            }
        }.awaitAll()

        subnet
    }

    private fun parseRetryAfter(connection: HttpURLConnection): Int {
        val body = readResponseBody(connection)
        return runCatching {
            JSONObject(body).optInt("retry_after", DEFAULT_RETRY_AFTER_SECONDS)
        }.getOrDefault(DEFAULT_RETRY_AFTER_SECONDS).coerceAtLeast(1)
    }

    private fun readResponseBody(connection: HttpURLConnection): String {
        val stream = try {
            if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        } catch (_: Exception) {
            connection.errorStream
        } ?: return ""
        return BufferedReader(InputStreamReader(stream)).use { it.readText() }
    }
}
