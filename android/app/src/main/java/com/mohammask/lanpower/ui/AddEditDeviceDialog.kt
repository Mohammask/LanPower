package com.mohammask.lanpower.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohammask.lanpower.R
import com.mohammask.lanpower.model.DEFAULT_AGENT_PORT
import com.mohammask.lanpower.model.Device
import com.mohammask.lanpower.model.DiscoveredDevice
import com.mohammask.lanpower.model.NetworkValidation
import com.mohammask.lanpower.model.PinVerificationState
import com.mohammask.lanpower.ui.theme.LocalLanPowerStatusColors
import com.mohammask.lanpower.ui.theme.MonoFontFamily
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditDeviceDialog(
    initialDevice: Device?,
    focusPinOnOpen: Boolean,
    sheetTransientStateFlow: StateFlow<SheetTransientState>,
    onStartScan: (port: Int) -> Unit,
    onStopScan: () -> Unit,
    onAutoFetch: suspend (ip: String, port: Int) -> Result<AutoFetchResult>,
    onVerifyPin: (ip: String, port: Int, token: String) -> Unit,
    onResetPinVerification: () -> Unit,
    onSave: (Device) -> Unit,
    onDelete: ((Device) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val pinFocusRequester = remember { FocusRequester() }
    val statusColors = LocalLanPowerStatusColors.current
    val deviceKey = initialDevice?.id ?: "new_device"

    // Strictly local input states so background status polling never recomposes open text fields
    var name by rememberSaveable(deviceKey) { mutableStateOf(initialDevice?.name.orEmpty()) }
    var ip by rememberSaveable(deviceKey) { mutableStateOf(initialDevice?.ip.orEmpty()) }
    var portText by rememberSaveable(deviceKey) {
        mutableStateOf((initialDevice?.port ?: DEFAULT_AGENT_PORT).toString())
    }
    var mac by rememberSaveable(deviceKey) { mutableStateOf(initialDevice?.mac.orEmpty()) }
    var broadcastIp by rememberSaveable(deviceKey) {
        mutableStateOf(initialDevice?.broadcastIp.orEmpty())
    }
    var broadcastManuallyEdited by rememberSaveable(deviceKey) {
        mutableStateOf(initialDevice != null && initialDevice.broadcastIp.isNotBlank())
    }
    var authToken by rememberSaveable(deviceKey) {
        mutableStateOf(initialDevice?.authToken.orEmpty())
    }
    var authRequired by rememberSaveable(deviceKey) {
        mutableStateOf(initialDevice?.authRequired ?: false)
    }
    var pinVisible by rememberSaveable(deviceKey) { mutableStateOf(false) }

    var isFetching by remember(deviceKey) { mutableStateOf(false) }
    var fetchMessage by remember(deviceKey) { mutableStateOf<String?>(null) }
    var fetchError by remember(deviceKey) { mutableStateOf(false) }

    // Derived validation avoids running checks directly inside onValueChange callbacks
    val parsedPort by remember { derivedStateOf { portText.trim().toIntOrNull() } }
    val isIpValid by remember { derivedStateOf { NetworkValidation.isValidIpv4(ip) } }
    val isPortValid by remember { derivedStateOf { NetworkValidation.isValidPort(parsedPort) } }
    val isMacValid by remember { derivedStateOf { NetworkValidation.isValidMac(mac) } }
    val isBroadcastValid by remember {
        derivedStateOf { broadcastIp.isBlank() || NetworkValidation.isValidIpv4(broadcastIp) }
    }
    val canSave by remember {
        derivedStateOf {
            name.isNotBlank() && isIpValid && isPortValid && isMacValid && isBroadcastValid
        }
    }

    // Lazily auto-compute broadcast IP when a valid IPv4 is entered without blocking keystrokes
    LaunchedEffect(ip, broadcastManuallyEdited) {
        if (!broadcastManuallyEdited && NetworkValidation.isValidIpv4(ip)) {
            val computed = NetworkValidation.computeBroadcastIp(ip)
            if (computed.isNotEmpty()) {
                broadcastIp = computed
            }
        }
    }

    LaunchedEffect(focusPinOnOpen) {
        if (focusPinOnOpen) {
            runCatching { pinFocusRequester.requestFocus() }
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            onStopScan()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (initialDevice == null) {
                            stringResource(R.string.dialog_add_device_title)
                        } else {
                            stringResource(R.string.dialog_edit_device_title)
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Scan your local network or enter details manually",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = {
                        onStopScan()
                        onDismiss()
                    },
                    modifier = Modifier.testTag("close_sheet_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.action_cancel),
                    )
                }
            }

            // LAN Auto-Discovery Banner collects scan state locally so scan ticks don't recompose inputs
            LanAutoDiscoveryCard(
                sheetTransientStateFlow = sheetTransientStateFlow,
                port = parsedPort ?: DEFAULT_AGENT_PORT,
                isPortValid = isPortValid,
                onStartScan = onStartScan,
                onStopScan = onStopScan,
                onSelectDiscovered = { discovered ->
                    name = discovered.hostname
                    ip = discovered.ip
                    portText = discovered.port.toString()
                    mac = NetworkValidation.normalizeMac(discovered.mac)
                    broadcastIp = discovered.broadcastIp
                    broadcastManuallyEdited = true
                    authRequired = discovered.authRequired
                    fetchMessage = "Filled from ${discovered.hostname}"
                    fetchError = false
                    onResetPinVerification()
                    if (discovered.authRequired && authToken.isNotBlank()) {
                        onVerifyPin(discovered.ip, discovered.port, authToken)
                    }
                },
            )

            // Manual Configuration Fields
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Device Name") },
                placeholder = { Text("homeserver") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("device_name_input"),
            )

            // IP Address + Auto-Fetch Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { newIp ->
                        ip = newIp.filterNot { it.isWhitespace() }
                        if (fetchMessage != null) fetchMessage = null
                        onResetPinVerification()
                    },
                    label = { Text("IPv4 Address") },
                    placeholder = { Text("192.168.1.50") },
                    singleLine = true,
                    isError = ip.isNotEmpty() && !isIpValid,
                    supportingText = if (ip.isNotEmpty() && !isIpValid) {
                        { Text("Enter valid IPv4 (e.g. 192.168.1.50)") }
                    } else null,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFontFamily),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("device_ip_input"),
                )

                FilledTonalButton(
                    onClick = {
                        val targetPort = parsedPort ?: DEFAULT_AGENT_PORT
                        isFetching = true
                        fetchMessage = null
                        fetchError = false
                        scope.launch {
                            val result = onAutoFetch(ip, targetPort)
                            isFetching = false
                            result.onSuccess { info ->
                                name = info.hostname
                                if (info.mac.isNotBlank()) {
                                    mac = NetworkValidation.normalizeMac(info.mac)
                                }
                                if (info.broadcastIp.isNotBlank()) {
                                    broadcastIp = info.broadcastIp
                                    broadcastManuallyEdited = true
                                }
                                authRequired = info.authRequired
                                val ifaceSuffix = info.networkInterface
                                    .takeIf { it.isNotBlank() }
                                    ?.let { " ($it)" }
                                    .orEmpty()
                                fetchMessage = "Fetched ${info.hostname}$ifaceSuffix"
                                fetchError = false
                                onResetPinVerification()
                                if (info.authRequired && authToken.isNotBlank()) {
                                    onVerifyPin(ip, targetPort, authToken)
                                }
                            }.onFailure { err ->
                                fetchMessage = err.message ?: "Failed to fetch device status"
                                fetchError = true
                            }
                        }
                    },
                    enabled = isIpValid && isPortValid && !isFetching,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .heightIn(min = 52.dp)
                        .testTag("auto_fetch_button"),
                ) {
                    if (isFetching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Sync,
                            contentDescription = stringResource(R.string.cd_auto_fetch),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.action_auto_fetch))
                }
            }

            if (!fetchMessage.isNullOrBlank()) {
                Text(
                    text = fetchMessage.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (fetchError) MaterialTheme.colorScheme.error else statusColors.online.content,
                )
            }

            // Port + Broadcast IP Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = { input ->
                        portText = input.filter { it.isDigit() }.take(5)
                        onResetPinVerification()
                    },
                    label = { Text("Port") },
                    placeholder = { Text(DEFAULT_AGENT_PORT.toString()) },
                    singleLine = true,
                    isError = portText.isNotEmpty() && !isPortValid,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFontFamily),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier
                        .width(116.dp)
                        .testTag("device_port_input"),
                )

                OutlinedTextField(
                    value = broadcastIp,
                    onValueChange = { value ->
                        val cleaned = value.filterNot { it.isWhitespace() }
                        broadcastIp = cleaned
                        broadcastManuallyEdited = cleaned.isNotEmpty()
                    },
                    label = { Text("Broadcast IP") },
                    placeholder = { Text("192.168.1.255") },
                    singleLine = true,
                    isError = broadcastIp.isNotEmpty() && !isBroadcastValid,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFontFamily),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("device_broadcast_input"),
                )
            }

            // MAC Address (lightweight '-' to ':' and uppercase normalization)
            OutlinedTextField(
                value = mac,
                onValueChange = { raw ->
                    mac = raw.replace('-', ':').uppercase(Locale.US).take(17)
                },
                label = { Text("MAC Address") },
                placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                singleLine = true,
                isError = mac.isNotEmpty() && !isMacValid,
                supportingText = {
                    val helper = if (mac.isNotEmpty() && !isMacValid) {
                        "Format must be XX:XX:XX:XX:XX:XX"
                    } else {
                        "Required for Wake-on-LAN (hyphens auto-convert to colons)"
                    }
                    Text(helper)
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFontFamily),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("device_mac_input"),
            )

            // PIN / Auth Token + Verify PIN
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = authToken,
                    onValueChange = {
                        authToken = it
                        onResetPinVerification()
                    },
                    label = {
                        Text(if (authRequired) "PIN / Token (Required by agent)" else "PIN / Token (Optional)")
                    },
                    placeholder = { Text("Leave blank if agent has no PIN") },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = if (authRequired) Icons.Rounded.Lock else Icons.Rounded.Key,
                            contentDescription = null,
                        )
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = { pinVisible = !pinVisible },
                            modifier = Modifier.testTag("toggle_pin_visibility_button"),
                        ) {
                            Icon(
                                imageVector = if (pinVisible) {
                                    Icons.Rounded.VisibilityOff
                                } else {
                                    Icons.Rounded.Visibility
                                },
                                contentDescription = stringResource(R.string.cd_toggle_pin_visibility),
                            )
                        }
                    },
                    visualTransformation = if (pinVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(pinFocusRequester)
                        .testTag("device_pin_input"),
                )

                PinVerificationRow(
                    sheetTransientStateFlow = sheetTransientStateFlow,
                    canVerify = isIpValid && isPortValid,
                    onVerifyClick = {
                        val targetPort = parsedPort ?: DEFAULT_AGENT_PORT
                        onVerifyPin(ip, targetPort, authToken)
                    },
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Action Footer
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (initialDevice != null && onDelete != null) {
                    TextButton(
                        onClick = {
                            onStopScan()
                            onDelete(initialDevice)
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("delete_device_sheet_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                OutlinedButton(
                    onClick = {
                        onStopScan()
                        onDismiss()
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("cancel_device_button"),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }

                Button(
                    onClick = {
                        val finalIp = ip.trim()
                        val finalPort = parsedPort ?: DEFAULT_AGENT_PORT
                        val finalBroadcast = broadcastIp.trim().ifBlank {
                            NetworkValidation.computeBroadcastIp(finalIp)
                        }
                        val device = Device(
                            id = initialDevice?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            ip = finalIp,
                            port = finalPort,
                            mac = NetworkValidation.normalizeMac(mac),
                            broadcastIp = finalBroadcast,
                            authToken = authToken,
                            authRequired = authRequired || authToken.isNotBlank(),
                        )
                        onStopScan()
                        onSave(device)
                    },
                    enabled = canSave,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("save_device_button"),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

@Composable
private fun LanAutoDiscoveryCard(
    sheetTransientStateFlow: StateFlow<SheetTransientState>,
    port: Int,
    isPortValid: Boolean,
    onStartScan: (port: Int) -> Unit,
    onStopScan: () -> Unit,
    onSelectDiscovered: (DiscoveredDevice) -> Unit,
) {
    val transientState by sheetTransientStateFlow.collectAsStateWithLifecycle()
    val scanState = transientState.scanState

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Radar,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(8.dp),
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "LAN Auto-Discovery",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = scanState.subnetLabel ?: "Port $port",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = MonoFontFamily,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (scanState.isScanning) {
                    OutlinedButton(
                        onClick = onStopScan,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("stop_scan_button"),
                    ) {
                        Text(
                            text = stringResource(R.string.action_stop_scan),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                } else {
                    FilledTonalButton(
                        onClick = { onStartScan(port) },
                        enabled = isPortValid,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("scan_lan_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = stringResource(R.string.action_scan_lan),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }

            AnimatedVisibility(visible = scanState.isScanning) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val progress = if (scanState.totalCount > 0) {
                        scanState.scannedCount.toFloat() / scanState.totalCount.toFloat()
                    } else {
                        0f
                    }
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                    )
                    Text(
                        text = "Scanning hosts… ${scanState.scannedCount} / ${scanState.totalCount}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = MonoFontFamily,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!scanState.errorMessage.isNullOrBlank()) {
                Text(
                    text = scanState.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (!scanState.isScanning &&
                scanState.totalCount > 0 &&
                scanState.discoveredDevices.isEmpty()
            ) {
                Text(
                    text = "No LanPower agents found on port $port. Ensure the agent is running on your Linux PC.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (scanState.discoveredDevices.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Found ${scanState.discoveredDevices.size} device(s) — tap to fill",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    scanState.discoveredDevices.forEach { discovered ->
                        DiscoveredDeviceRow(
                            device = discovered,
                            onSelect = { onSelectDiscovered(discovered) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PinVerificationRow(
    sheetTransientStateFlow: StateFlow<SheetTransientState>,
    canVerify: Boolean,
    onVerifyClick: () -> Unit,
) {
    val transientState by sheetTransientStateFlow.collectAsStateWithLifecycle()
    val pinState = transientState.pinVerificationState

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        PinVerificationBadge(
            state = pinState,
            modifier = Modifier.weight(1f),
        )

        TextButton(
            onClick = onVerifyClick,
            enabled = canVerify && pinState !is PinVerificationState.Verifying,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("verify_pin_button"),
        ) {
            if (pinState is PinVerificationState.Verifying) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(stringResource(R.string.action_verify_pin))
        }
    }
}

@Composable
private fun DiscoveredDeviceRow(
    device: DiscoveredDevice,
    onSelect: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onSelect)
            .testTag("discovered_device_${device.ip}"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Computer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = device.hostname,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (device.authRequired) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = stringResource(R.string.cd_auth_required),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Text(
                    text = "${device.ip}:${device.port}  •  ${device.mac}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PinVerificationBadge(
    state: PinVerificationState,
    modifier: Modifier = Modifier,
) {
    val statusColors = LocalLanPowerStatusColors.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (state) {
            PinVerificationState.Idle -> {
                Text(
                    text = "Test PIN safely via /verify",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PinVerificationState.Verifying -> {
                Text(
                    text = "Verifying PIN…",
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColors.checking.content,
                )
            }
            PinVerificationState.Valid -> {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = statusColors.online.content,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "PIN verified",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColors.online.content,
                )
            }
            PinVerificationState.Invalid -> {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Incorrect PIN",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            is PinVerificationState.RateLimited -> {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Too many failed PIN attempts. Try again in ${state.retryAfterSeconds}s.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            is PinVerificationState.Unreachable -> {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
