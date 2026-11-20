package com.mohammask.lanpower.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mohammask.lanpower.data.DeviceRepository
import com.mohammask.lanpower.model.Device
import com.mohammask.lanpower.model.DeviceStatus
import com.mohammask.lanpower.model.DiscoveredDevice
import com.mohammask.lanpower.model.NetworkValidation
import com.mohammask.lanpower.model.PinVerificationState
import com.mohammask.lanpower.model.PowerAction
import com.mohammask.lanpower.network.CommandResult
import com.mohammask.lanpower.network.LanPowerClient
import com.mohammask.lanpower.network.StatusResult
import com.mohammask.lanpower.network.VerifyResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class ScanUiState(
    val isScanning: Boolean = false,
    val scannedCount: Int = 0,
    val totalCount: Int = 0,
    val subnetLabel: String? = null,
    val discoveredDevices: List<DiscoveredDevice> = emptyList(),
    val errorMessage: String? = null,
)

data class SheetTransientState(
    val scanState: ScanUiState = ScanUiState(),
    val pinVerificationState: PinVerificationState = PinVerificationState.Idle,
)

data class AutoFetchResult(
    val hostname: String,
    val mac: String,
    val broadcastIp: String,
    val networkInterface: String,
    val authRequired: Boolean,
)

data class SnackbarEvent(
    val message: String,
    val actionLabel: String? = null,
    val editDeviceId: String? = null,
)

data class MainUiState(
    val devices: List<Device> = emptyList(),
    val statuses: Map<String, DeviceStatus> = emptyMap(),
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DeviceRepository(application.applicationContext)
    private val client = LanPowerClient()

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // Kept separate from MainUiState so background status polling never recomposes open sheet fields
    private val _sheetTransientState = MutableStateFlow(SheetTransientState())
    val sheetTransientState: StateFlow<SheetTransientState> = _sheetTransientState.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<SnackbarEvent>(extraBufferCapacity = 4)
    val snackbarEvents: SharedFlow<SnackbarEvent> = _snackbarEvents.asSharedFlow()

    private val pollingJobs = ConcurrentHashMap<String, Job>()
    private var manualRefreshJob: Job? = null
    private var scanJob: Job? = null
    private var hasPerformedInitialCheck = false

    companion object {
        private const val MIN_MANUAL_REFRESH_DURATION_MS = 350L
        private const val WAKE_POLL_INTERVAL_MS = 2_500L
        private const val WAKE_TIMEOUT_MS = 60_000L
        private const val SHUTDOWN_POLL_INTERVAL_MS = 2_000L
        private const val SHUTDOWN_TIMEOUT_MS = 30_000L
        private const val REBOOT_INITIAL_DELAY_MS = 5_000L
        private const val REBOOT_POLL_INTERVAL_MS = 3_000L
        private const val REBOOT_TIMEOUT_MS = 60_000L
    }

    init {
        viewModelScope.launch {
            repository.devicesFlow.collect { savedDevices ->
                val savedIds = savedDevices.map { it.id }.toSet()
                pollingJobs.keys.filterNot { it in savedIds }.forEach { removedId ->
                    pollingJobs.remove(removedId)?.cancel()
                }

                _uiState.update { current ->
                    val updatedStatuses = buildMap {
                        for (device in savedDevices) {
                            put(device.id, current.statuses[device.id] ?: DeviceStatus.Checking)
                        }
                    }
                    current.copy(
                        devices = savedDevices,
                        statuses = updatedStatuses,
                        isInitialLoading = false,
                    )
                }

                if (!hasPerformedInitialCheck) {
                    hasPerformedInitialCheck = true
                    if (savedDevices.isNotEmpty()) {
                        checkAllDevicesSilently()
                    }
                }
            }
        }
    }

    /**
     * Triggered only by user pull-to-refresh or TopAppBar refresh button.
     * Completely decoupled from initial check and per-device Booting/Shutdown/Reboot polling.
     */
    fun refreshAll() {
        val currentDevices = _uiState.value.devices
        if (currentDevices.isEmpty()) {
            _uiState.update { it.copy(isRefreshing = false) }
            return
        }
        if (manualRefreshJob?.isActive == true) return

        manualRefreshJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            _uiState.update { state ->
                val nextStatuses = state.statuses.toMutableMap()
                for (device in currentDevices) {
                    val activeJob = pollingJobs[device.id]
                    if (activeJob == null || !activeJob.isActive) {
                        nextStatuses[device.id] = DeviceStatus.Checking
                    }
                }
                state.copy(statuses = nextStatuses, isRefreshing = true)
            }

            try {
                coroutineScope {
                    currentDevices.map { device ->
                        async {
                            val activeJob = pollingJobs[device.id]
                            if (activeJob != null && activeJob.isActive) return@async
                            checkSingleDevice(device)
                        }
                    }.awaitAll()
                }
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed < MIN_MANUAL_REFRESH_DURATION_MS) {
                    delay(MIN_MANUAL_REFRESH_DURATION_MS - elapsed)
                }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    private fun checkAllDevicesSilently() {
        val currentDevices = _uiState.value.devices
        if (currentDevices.isEmpty()) return

        viewModelScope.launch {
            coroutineScope {
                currentDevices.map { device ->
                    async {
                        val activeJob = pollingJobs[device.id]
                        if (activeJob != null && activeJob.isActive) return@async
                        checkSingleDevice(device)
                    }
                }.awaitAll()
            }
        }
    }

    private suspend fun checkSingleDevice(device: Device) {
        val result = client.getStatus(device.ip, device.port, LanPowerClient.STATUS_TIMEOUT_MS)
        when (result) {
            is StatusResult.Online -> {
                updateDeviceStatus(device.id, DeviceStatus.Online)
                if (device.authRequired != result.authRequired) {
                    repository.updateDeviceAuthRequired(device.id, result.authRequired)
                }
            }
            is StatusResult.Offline -> {
                updateDeviceStatus(device.id, DeviceStatus.Offline)
            }
        }
    }

    fun saveDevice(device: Device) {
        viewModelScope.launch {
            repository.upsertDevice(device)
            pollingJobs.remove(device.id)?.cancel()
            updateDeviceStatus(device.id, DeviceStatus.Checking)
            checkSingleDevice(device)
        }
    }

    fun deleteDevice(device: Device) {
        pollingJobs.remove(device.id)?.cancel()
        viewModelScope.launch {
            repository.deleteDevice(device.id)
            emitSnackbar("Removed ${device.name}")
        }
    }

    fun wakeDevice(device: Device) {
        pollingJobs.remove(device.id)?.cancel()
        val job = viewModelScope.launch {
            val wolResult = client.sendWakeOnLan(device.mac, device.broadcastIp)
            if (wolResult.isFailure) {
                emitSnackbar(
                    "Wake-on-LAN failed: ${wolResult.exceptionOrNull()?.message ?: "Unknown error"}",
                )
                return@launch
            }

            updateDeviceStatus(device.id, DeviceStatus.Booting)
            emitSnackbar("Magic packet sent to ${device.name}")

            val startTime = System.currentTimeMillis()
            while (isActive && System.currentTimeMillis() - startTime < WAKE_TIMEOUT_MS) {
                delay(WAKE_POLL_INTERVAL_MS)
                val status = client.getStatus(device.ip, device.port, LanPowerClient.STATUS_TIMEOUT_MS)
                if (status is StatusResult.Online) {
                    updateDeviceStatus(device.id, DeviceStatus.Online)
                    if (device.authRequired != status.authRequired) {
                        repository.updateDeviceAuthRequired(device.id, status.authRequired)
                    }
                    emitSnackbar("${device.name} is now online")
                    return@launch
                }
            }

            if (isActive) {
                updateDeviceStatus(device.id, DeviceStatus.Offline)
                emitSnackbar("${device.name} did not come online within 60s")
            }
        }
        pollingJobs[device.id] = job
        job.invokeOnCompletion { pollingJobs.remove(device.id, job) }
    }

    fun executePowerAction(device: Device, action: PowerAction) {
        pollingJobs.remove(device.id)?.cancel()
        val job = viewModelScope.launch {
            val result = if (action == PowerAction.Sleep) {
                client.sleep(device)
            } else {
                client.sendPowerCommand(
                    ip = device.ip,
                    port = device.port,
                    action = action,
                    token = device.authToken,
                )
            }

            when (result) {
                is CommandResult.Unauthorized -> {
                    if (!device.authRequired) {
                        repository.updateDeviceAuthRequired(device.id, true)
                    }
                    _snackbarEvents.tryEmit(
                        SnackbarEvent(
                            message = "Incorrect or missing PIN",
                            actionLabel = "Edit PIN",
                            editDeviceId = device.id,
                        ),
                    )
                }

                is CommandResult.RateLimited -> {
                    emitSnackbar(
                        "Too many failed PIN attempts. Try again in ${result.retryAfterSeconds}s.",
                    )
                }

                is CommandResult.Error -> {
                    emitSnackbar("${device.name}: ${result.message}")
                    checkSingleDevice(device)
                }

                is CommandResult.Success -> {
                    when (action) {
                        PowerAction.Sleep -> pollUntilAsleep(device)
                        PowerAction.Shutdown -> pollUntilShutdown(device)
                        PowerAction.Reboot -> pollUntilRebooted(device)
                    }
                }
            }
        }
        pollingJobs[device.id] = job
        job.invokeOnCompletion { pollingJobs.remove(device.id, job) }
    }

    private suspend fun pollUntilAsleep(device: Device) {
        updateDeviceStatus(device.id, DeviceStatus.Sleeping)
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < SHUTDOWN_TIMEOUT_MS) {
            delay(SHUTDOWN_POLL_INTERVAL_MS)
            val status = client.getStatus(device.ip, device.port, LanPowerClient.STATUS_TIMEOUT_MS)
            if (status is StatusResult.Offline) {
                updateDeviceStatus(device.id, DeviceStatus.Offline)
                emitSnackbar("${device.name} is now asleep")
                return
            }
        }
        checkSingleDevice(device)
    }

    private suspend fun pollUntilShutdown(device: Device) {
        updateDeviceStatus(device.id, DeviceStatus.ShuttingDown)
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < SHUTDOWN_TIMEOUT_MS) {
            delay(SHUTDOWN_POLL_INTERVAL_MS)
            val status = client.getStatus(device.ip, device.port, LanPowerClient.STATUS_TIMEOUT_MS)
            if (status is StatusResult.Offline) {
                updateDeviceStatus(device.id, DeviceStatus.Offline)
                emitSnackbar("${device.name} shut down")
                return
            }
        }
        checkSingleDevice(device)
    }

    private suspend fun pollUntilRebooted(device: Device) {
        updateDeviceStatus(device.id, DeviceStatus.Rebooting)
        delay(REBOOT_INITIAL_DELAY_MS)

        val pollStart = System.currentTimeMillis()
        while (System.currentTimeMillis() - pollStart < REBOOT_TIMEOUT_MS) {
            delay(REBOOT_POLL_INTERVAL_MS)
            val status = client.getStatus(device.ip, device.port, LanPowerClient.STATUS_TIMEOUT_MS)
            if (status is StatusResult.Online) {
                updateDeviceStatus(device.id, DeviceStatus.Online)
                if (device.authRequired != status.authRequired) {
                    repository.updateDeviceAuthRequired(device.id, status.authRequired)
                }
                emitSnackbar("${device.name} is back online")
                return
            }
        }
        updateDeviceStatus(device.id, DeviceStatus.Offline)
        emitSnackbar("${device.name} did not come back online within 60s")
    }

    fun startLanScan(port: Int) {
        scanJob?.cancel()
        val subnetPreview = client.detectLocalSubnet(getApplication())
        if (subnetPreview == null) {
            _sheetTransientState.update {
                it.copy(
                    scanState = ScanUiState(
                        isScanning = false,
                        errorMessage = "Connect to a Wi-Fi or Ethernet LAN to scan.",
                    ),
                )
            }
            return
        }

        val octets = subnetPreview.localIp.split('.')
        val subnetLabel = if (octets.size == 4) {
            "${octets[0]}.${octets[1]}.${octets[2]}.0/${subnetPreview.prefixLength}"
        } else {
            subnetPreview.localIp
        }

        _sheetTransientState.update {
            it.copy(
                scanState = ScanUiState(
                    isScanning = true,
                    scannedCount = 0,
                    totalCount = subnetPreview.candidateIps.size,
                    subnetLabel = subnetLabel,
                    discoveredDevices = emptyList(),
                    errorMessage = null,
                ),
            )
        }

        scanJob = viewModelScope.launch {
            try {
                client.scanLan(
                    context = getApplication(),
                    port = port,
                    onProgress = { scanned, total ->
                        _sheetTransientState.update { state ->
                            state.copy(
                                scanState = state.scanState.copy(
                                    scannedCount = scanned,
                                    totalCount = total,
                                ),
                            )
                        }
                    },
                    onDeviceFound = { found ->
                        _sheetTransientState.update { state ->
                            val existing = state.scanState.discoveredDevices
                            if (existing.any { it.ip == found.ip && it.port == found.port }) {
                                state
                            } else {
                                val updated = (existing + found).sortedBy { ipSortKey(it.ip) }
                                state.copy(
                                    scanState = state.scanState.copy(discoveredDevices = updated),
                                )
                            }
                        }
                    },
                )
            } catch (_: CancellationException) {
                // Scan cancelled by user or sheet dismissal
            } finally {
                _sheetTransientState.update { state ->
                    state.copy(scanState = state.scanState.copy(isScanning = false))
                }
            }
        }
    }

    fun stopLanScan() {
        scanJob?.cancel()
        scanJob = null
        _sheetTransientState.update { state ->
            state.copy(scanState = state.scanState.copy(isScanning = false))
        }
    }

    suspend fun autoFetchFromIp(ip: String, port: Int): Result<AutoFetchResult> {
        if (!NetworkValidation.isValidIpv4(ip)) {
            return Result.failure(IllegalArgumentException("Enter a valid IPv4 address first"))
        }
        val result = client.getStatus(ip.trim(), port, LanPowerClient.STATUS_TIMEOUT_MS)
        return when (result) {
            is StatusResult.Online -> {
                val localSubnet = client.detectLocalSubnet(getApplication())
                val prefix = localSubnet?.prefixLength ?: 24
                val broadcast = NetworkValidation.computeBroadcastIp(ip.trim(), prefix)
                    .ifBlank { NetworkValidation.computeBroadcastIp(ip.trim(), 24) }
                Result.success(
                    AutoFetchResult(
                        hostname = result.hostname,
                        mac = result.mac,
                        broadcastIp = broadcast,
                        networkInterface = result.networkInterface,
                        authRequired = result.authRequired,
                    ),
                )
            }
            is StatusResult.Offline -> {
                Result.failure(
                    IllegalStateException(
                        "No LanPower agent responded at ${ip.trim()}:$port",
                    ),
                )
            }
        }
    }

    fun verifyPin(ip: String, port: Int, token: String) {
        if (!NetworkValidation.isValidIpv4(ip) || !NetworkValidation.isValidPort(port)) {
            _sheetTransientState.update {
                it.copy(
                    pinVerificationState = PinVerificationState.Unreachable(
                        "Enter a valid IP and port first",
                    ),
                )
            }
            return
        }
        viewModelScope.launch {
            _sheetTransientState.update {
                it.copy(pinVerificationState = PinVerificationState.Verifying)
            }
            val outcome = when (val result = client.verifyPin(ip.trim(), port, token)) {
                is VerifyResult.Authorized -> PinVerificationState.Valid
                is VerifyResult.Unauthorized -> PinVerificationState.Invalid
                is VerifyResult.RateLimited -> PinVerificationState.RateLimited(
                    result.retryAfterSeconds,
                )
                is VerifyResult.Error -> PinVerificationState.Unreachable(result.message)
            }
            _sheetTransientState.update { it.copy(pinVerificationState = outcome) }
        }
    }

    fun resetPinVerification() {
        _sheetTransientState.update {
            if (it.pinVerificationState == PinVerificationState.Idle) {
                it
            } else {
                it.copy(pinVerificationState = PinVerificationState.Idle)
            }
        }
    }

    fun resetSheetState() {
        stopLanScan()
        _sheetTransientState.value = SheetTransientState()
    }

    private fun updateDeviceStatus(deviceId: String, status: DeviceStatus) {
        _uiState.update { state ->
            if (state.devices.none { it.id == deviceId }) {
                state
            } else {
                state.copy(statuses = state.statuses + (deviceId to status))
            }
        }
    }

    private fun emitSnackbar(message: String) {
        _snackbarEvents.tryEmit(SnackbarEvent(message = message))
    }

    private fun ipSortKey(ip: String): Long {
        val parts = ip.split('.')
        if (parts.size != 4) return Long.MAX_VALUE
        var value = 0L
        for (part in parts) {
            value = (value shl 8) or (part.toLongOrNull() ?: 0L)
        }
        return value
    }

    override fun onCleared() {
        super.onCleared()
        manualRefreshJob?.cancel()
        scanJob?.cancel()
        pollingJobs.values.forEach { it.cancel() }
        pollingJobs.clear()
    }
}
