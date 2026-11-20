package com.mohammask.lanpower.model

import java.util.Locale
import java.util.UUID

const val DEFAULT_AGENT_PORT = 8888

data class Device(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val ip: String,
    val port: Int = DEFAULT_AGENT_PORT,
    val mac: String,
    val broadcastIp: String,
    val authToken: String = "",
    val authRequired: Boolean = false,
)

enum class DeviceStatus {
    Online,
    Offline,
    Checking,
    Booting,
    Sleeping,
    ShuttingDown,
    Rebooting;

    val isTransitioning: Boolean
        get() = this == Checking ||
            this == Booting ||
            this == Sleeping ||
            this == ShuttingDown ||
            this == Rebooting
}

enum class PowerAction(val endpoint: String) {
    Sleep("sleep"),
    Reboot("reboot"),
    Shutdown("poweroff"),
}

data class DiscoveredDevice(
    val hostname: String,
    val ip: String,
    val port: Int,
    val mac: String,
    val broadcastIp: String,
    val networkInterface: String,
    val authRequired: Boolean,
    val version: String,
)

sealed interface PinVerificationState {
    data object Idle : PinVerificationState
    data object Verifying : PinVerificationState
    data object Valid : PinVerificationState
    data object Invalid : PinVerificationState
    data class RateLimited(val retryAfterSeconds: Int) : PinVerificationState
    data class Unreachable(val message: String) : PinVerificationState
}

object NetworkValidation {
    private val MAC_REGEX = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

    fun normalizeMac(raw: String): String =
        raw.trim()
            .replace('-', ':')
            .replace(" ", "")
            .uppercase(Locale.US)

    fun isValidMac(raw: String): Boolean = MAC_REGEX.matches(normalizeMac(raw))

    fun isValidIpv4(raw: String): Boolean {
        val trimmed = raw.trim()
        val parts = trimmed.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) {
                false
            } else {
                val value = part.toIntOrNull() ?: return@all false
                value in 0..255 && (part.length == 1 || !part.startsWith('0'))
            }
        }
    }

    fun isValidPort(port: Int?): Boolean = port != null && port in 1..65535

    fun computeBroadcastIp(ip: String, prefixLength: Int = 24): String {
        if (!isValidIpv4(ip)) return ""
        val octets = ip.trim().split('.').map { it.toInt() }
        val ipInt = (octets[0] shl 24) or
            (octets[1] shl 16) or
            (octets[2] shl 8) or
            octets[3]
        val clampedPrefix = prefixLength.coerceIn(0, 32)
        val mask = if (clampedPrefix == 0) 0 else (-1 shl (32 - clampedPrefix))
        val broadcastInt = (ipInt and mask) or mask.inv()
        return "${(broadcastInt ushr 24) and 0xFF}." +
            "${(broadcastInt ushr 16) and 0xFF}." +
            "${(broadcastInt ushr 8) and 0xFF}." +
            "${broadcastInt and 0xFF}"
    }
}
