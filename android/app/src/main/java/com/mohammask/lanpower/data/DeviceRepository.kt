package com.mohammask.lanpower.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mohammask.lanpower.model.DEFAULT_AGENT_PORT
import com.mohammask.lanpower.model.Device
import com.mohammask.lanpower.model.NetworkValidation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.lanPowerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "lanpower_devices_store",
)

class DeviceRepository(private val context: Context) {

    private val devicesKey = stringPreferencesKey("devices_json")

    val devicesFlow: Flow<List<Device>> = context.lanPowerDataStore.data.map { prefs ->
        val raw = prefs[devicesKey]
        if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            deserializeDevices(raw)
        }
    }

    suspend fun upsertDevice(device: Device) {
        context.lanPowerDataStore.edit { prefs ->
            val current = deserializeDevices(prefs[devicesKey].orEmpty()).toMutableList()
            val normalized = device.copy(
                name = device.name.trim(),
                ip = device.ip.trim(),
                mac = NetworkValidation.normalizeMac(device.mac),
                broadcastIp = device.broadcastIp.trim().ifBlank {
                    NetworkValidation.computeBroadcastIp(device.ip)
                },
            )
            val existingIndex = current.indexOfFirst { it.id == normalized.id }
            if (existingIndex >= 0) {
                current[existingIndex] = normalized
            } else {
                current.add(normalized)
            }
            prefs[devicesKey] = serializeDevices(current)
        }
    }

    suspend fun deleteDevice(deviceId: String) {
        context.lanPowerDataStore.edit { prefs ->
            val updated = deserializeDevices(prefs[devicesKey].orEmpty())
                .filterNot { it.id == deviceId }
            prefs[devicesKey] = serializeDevices(updated)
        }
    }

    suspend fun updateDeviceAuthRequired(deviceId: String, authRequired: Boolean) {
        context.lanPowerDataStore.edit { prefs ->
            val current = deserializeDevices(prefs[devicesKey].orEmpty())
            val target = current.firstOrNull { it.id == deviceId } ?: return@edit
            if (target.authRequired == authRequired) return@edit
            val updated = current.map { device ->
                if (device.id == deviceId) device.copy(authRequired = authRequired) else device
            }
            prefs[devicesKey] = serializeDevices(updated)
        }
    }

    private fun serializeDevices(devices: List<Device>): String {
        val array = JSONArray()
        for (device in devices) {
            val obj = JSONObject().apply {
                put("id", device.id)
                put("name", device.name)
                put("ip", device.ip)
                put("port", device.port)
                put("mac", device.mac)
                put("broadcastIp", device.broadcastIp)
                put("authToken", device.authToken)
                put("authRequired", device.authRequired)
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun deserializeDevices(raw: String): List<Device> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val ip = obj.optString("ip", "").trim()
                    val mac = NetworkValidation.normalizeMac(obj.optString("mac", ""))
                    if (ip.isEmpty() && mac.isEmpty()) continue
                    add(
                        Device(
                            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                            name = obj.optString("name", ip).ifBlank { ip },
                            ip = ip,
                            port = obj.optInt("port", DEFAULT_AGENT_PORT).coerceIn(1, 65535),
                            mac = mac,
                            broadcastIp = obj.optString("broadcastIp", "").ifBlank {
                                NetworkValidation.computeBroadcastIp(ip)
                            },
                            authToken = obj.optString("authToken", ""),
                            authRequired = obj.optBoolean("authRequired", false),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
