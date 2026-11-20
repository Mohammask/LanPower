package com.mohammask.lanpower.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mohammask.lanpower.R
import com.mohammask.lanpower.model.Device
import com.mohammask.lanpower.model.DeviceStatus
import com.mohammask.lanpower.model.PowerAction
import com.mohammask.lanpower.ui.theme.LanPowerStatusColors
import com.mohammask.lanpower.ui.theme.LocalLanPowerStatusColors
import com.mohammask.lanpower.ui.theme.MonoFontFamily
import com.mohammask.lanpower.ui.theme.StatusColorToken
import kotlinx.coroutines.launch

private data class PowerConfirmRequest(
    val device: Device,
    val action: PowerAction,
)

private data class SheetRequest(
    val device: Device? = null,
    val focusPin: Boolean = false,
)

@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    var sheetRequest by remember { mutableStateOf<SheetRequest?>(null) }
    var powerConfirmRequest by remember { mutableStateOf<PowerConfirmRequest?>(null) }
    var deleteConfirmDevice by remember { mutableStateOf<Device?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.snackbarEvents.collect { event ->
            val result = snackbarHostState.showSnackbar(
                message = event.message,
                actionLabel = event.actionLabel,
                duration = if (event.actionLabel != null) {
                    SnackbarDuration.Long
                } else {
                    SnackbarDuration.Short
                },
            )
            if (result == SnackbarResult.ActionPerformed && event.editDeviceId != null) {
                val target = viewModel.uiState.value.devices.firstOrNull {
                    it.id == event.editDeviceId
                }
                if (target != null) {
                    viewModel.resetSheetState()
                    sheetRequest = SheetRequest(device = target, focusPin = true)
                }
            }
        }
    }

    DashboardScaffold(
        viewModel = viewModel,
        snackbarHostState = snackbarHostState,
        onAddDeviceClick = {
            viewModel.resetSheetState()
            sheetRequest = SheetRequest(device = null)
        },
        onCopyCommand = { cmd, confirmationMessage ->
            clipboardManager.setText(AnnotatedString(cmd))
            scope.launch {
                snackbarHostState.showSnackbar(confirmationMessage)
            }
        },
        onSleep = { powerConfirmRequest = PowerConfirmRequest(it, PowerAction.Sleep) },
        onReboot = { powerConfirmRequest = PowerConfirmRequest(it, PowerAction.Reboot) },
        onShutdown = { powerConfirmRequest = PowerConfirmRequest(it, PowerAction.Shutdown) },
        onEdit = { device ->
            viewModel.resetSheetState()
            sheetRequest = SheetRequest(device = device)
        },
        onDelete = { deleteConfirmDevice = it },
    )

    // Add / Edit Device Modal Sheet
    sheetRequest?.let { request ->
        AddEditDeviceDialog(
            initialDevice = request.device,
            focusPinOnOpen = request.focusPin,
            sheetTransientStateFlow = viewModel.sheetTransientState,
            onStartScan = { port -> viewModel.startLanScan(port) },
            onStopScan = { viewModel.stopLanScan() },
            onAutoFetch = { ip, port -> viewModel.autoFetchFromIp(ip, port) },
            onVerifyPin = { ip, port, token -> viewModel.verifyPin(ip, port, token) },
            onResetPinVerification = { viewModel.resetPinVerification() },
            onSave = { savedDevice ->
                viewModel.saveDevice(savedDevice)
                sheetRequest = null
            },
            onDelete = if (request.device != null) {
                { target ->
                    sheetRequest = null
                    deleteConfirmDevice = target
                }
            } else {
                null
            },
            onDismiss = {
                viewModel.resetSheetState()
                sheetRequest = null
            },
        )
    }

    // Power Action Confirmation Dialog (Sleep / Reboot / Shutdown)
    powerConfirmRequest?.let { req ->
        val statusColors = LocalLanPowerStatusColors.current
        val isShutdown = req.action == PowerAction.Shutdown
        val isSleep = req.action == PowerAction.Sleep
        AlertDialog(
            onDismissRequest = { powerConfirmRequest = null },
            icon = {
                Icon(
                    imageVector = when (req.action) {
                        PowerAction.Sleep -> Icons.Rounded.Bedtime
                        PowerAction.Reboot -> Icons.Rounded.RestartAlt
                        PowerAction.Shutdown -> Icons.Rounded.PowerSettingsNew
                    },
                    contentDescription = null,
                    tint = when (req.action) {
                        PowerAction.Sleep -> statusColors.sleeping.content
                        PowerAction.Reboot -> MaterialTheme.colorScheme.primary
                        PowerAction.Shutdown -> MaterialTheme.colorScheme.error
                    },
                )
            },
            title = {
                Text(
                    text = when (req.action) {
                        PowerAction.Sleep -> stringResource(R.string.dialog_sleep_title, req.device.name)
                        PowerAction.Reboot -> stringResource(R.string.dialog_reboot_title, req.device.name)
                        PowerAction.Shutdown -> stringResource(R.string.dialog_shutdown_title, req.device.name)
                    },
                )
            },
            text = {
                Text(
                    text = when (req.action) {
                        PowerAction.Sleep -> stringResource(
                            R.string.dialog_sleep_message,
                            req.device.name,
                            "${req.device.ip}:${req.device.port}",
                        )
                        PowerAction.Reboot -> stringResource(
                            R.string.dialog_reboot_message,
                            req.device.name,
                            "${req.device.ip}:${req.device.port}",
                        )
                        PowerAction.Shutdown -> stringResource(
                            R.string.dialog_shutdown_message,
                            req.device.name,
                            "${req.device.ip}:${req.device.port}",
                        )
                    },
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { powerConfirmRequest = null },
                    modifier = Modifier.testTag("cancel_power_action_button"),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.executePowerAction(req.device, req.action)
                        powerConfirmRequest = null
                    },
                    colors = when {
                        isShutdown -> ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        )
                        isSleep -> ButtonDefaults.buttonColors(
                            containerColor = statusColors.sleepAction.container,
                            contentColor = statusColors.sleepAction.content,
                        )
                        else -> ButtonDefaults.buttonColors()
                    },
                    modifier = Modifier.testTag("confirm_power_action_button"),
                ) {
                    Text(
                        text = when (req.action) {
                            PowerAction.Sleep -> stringResource(R.string.action_sleep)
                            PowerAction.Reboot -> stringResource(R.string.action_reboot)
                            PowerAction.Shutdown -> stringResource(R.string.action_shutdown)
                        },
                    )
                }
            },
        )
    }

    // Delete Confirmation Dialog
    deleteConfirmDevice?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteConfirmDevice = null },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(stringResource(R.string.dialog_delete_title, target.name)) },
            text = { Text(stringResource(R.string.dialog_delete_message)) },
            dismissButton = {
                TextButton(onClick = { deleteConfirmDevice = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteDevice(target)
                        deleteConfirmDevice = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.testTag("confirm_delete_button"),
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardScaffold(
    viewModel: MainViewModel,
    snackbarHostState: SnackbarHostState,
    onAddDeviceClick: () -> Unit,
    onCopyCommand: (command: String, confirmationMessage: String) -> Unit,
    onSleep: (Device) -> Unit,
    onReboot: (Device) -> Unit,
    onShutdown: (Device) -> Unit,
    onEdit: (Device) -> Unit,
    onDelete: (Device) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pullToRefreshState = rememberPullToRefreshState()

    val onlineCount = remember(uiState.devices, uiState.statuses) {
        uiState.devices.count { uiState.statuses[it.id] == DeviceStatus.Online }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            LanPowerTopBar(
                totalDevices = uiState.devices.size,
                onlineCount = onlineCount,
                isRefreshing = uiState.isRefreshing,
                onRefresh = { viewModel.refreshAll() },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddDeviceClick,
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.cd_add_device),
                    )
                },
                text = { Text("Add Device") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.testTag("add_device_fab"),
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refreshAll() },
            state = pullToRefreshState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullToRefreshState,
                    isRefreshing = uiState.isRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (!uiState.isInitialLoading && uiState.devices.isEmpty()) {
                EmptyDashboardState(
                    onAddClick = onAddDeviceClick,
                    onCopyCommand = onCopyCommand,
                )
            } else {
                DeviceDashboardContent(
                    devices = uiState.devices,
                    statuses = uiState.statuses,
                    onWake = { viewModel.wakeDevice(it) },
                    onSleep = onSleep,
                    onReboot = onReboot,
                    onShutdown = onShutdown,
                    onEdit = onEdit,
                    onDelete = onDelete,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanPowerTopBar(
    totalDevices: Int,
    onlineCount: Int,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val statusColors = LocalLanPowerStatusColors.current
    val spinTransition = rememberInfiniteTransition(label = "refreshSpin")
    val spinAngle by spinTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
        ),
        label = "refreshAngle",
    )

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
        ),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.PowerSettingsNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Column {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                    )
                    if (totalDevices > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (onlineCount > 0) {
                                            statusColors.online.dot
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        },
                                    ),
                            )
                            Text(
                                text = "$onlineCount of $totalDevices online",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        actions = {
            if (totalDevices > 0) {
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.testTag("refresh_devices_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.cd_refresh_devices),
                        modifier = Modifier.rotate(if (isRefreshing) spinAngle else 0f),
                    )
                }
            }
        },
    )
}

@Composable
private fun DeviceDashboardContent(
    devices: List<Device>,
    statuses: Map<String, DeviceStatus>,
    onWake: (Device) -> Unit,
    onSleep: (Device) -> Unit,
    onReboot: (Device) -> Unit,
    onShutdown: (Device) -> Unit,
    onEdit: (Device) -> Unit,
    onDelete: (Device) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        val isWideScreen = maxWidth >= 640.dp
        if (isWideScreen) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 340.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 1080.dp),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 8.dp,
                    bottom = 88.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(devices, key = { it.id }) { device ->
                    DeviceCard(
                        device = device,
                        status = statuses[device.id] ?: DeviceStatus.Offline,
                        onWake = { onWake(device) },
                        onSleep = { onSleep(device) },
                        onReboot = { onReboot(device) },
                        onShutdown = { onShutdown(device) },
                        onEdit = { onEdit(device) },
                        onDelete = { onDelete(device) },
                    )
                }
                if (devices.isNotEmpty()) {
                    item(
                        key = "creator_footer",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CreatorFooter()
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 88.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(devices, key = { it.id }) { device ->
                    DeviceCard(
                        device = device,
                        status = statuses[device.id] ?: DeviceStatus.Offline,
                        onWake = { onWake(device) },
                        onSleep = { onSleep(device) },
                        onReboot = { onReboot(device) },
                        onShutdown = { onShutdown(device) },
                        onEdit = { onEdit(device) },
                        onDelete = { onDelete(device) },
                    )
                }
                if (devices.isNotEmpty()) {
                    item(key = "creator_footer") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CreatorFooter()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: Device,
    status: DeviceStatus,
    onWake: () -> Unit,
    onSleep: () -> Unit,
    onReboot: () -> Unit,
    onShutdown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val statusColors = LocalLanPowerStatusColors.current
    val token = status.toColorToken(statusColors)
    var menuExpanded by remember { mutableStateOf(false) }

    val borderColor by animateColorAsState(
        targetValue = when (status) {
            DeviceStatus.Online -> token.dot.copy(alpha = 0.42f)
            DeviceStatus.Booting, DeviceStatus.Sleeping, DeviceStatus.ShuttingDown, DeviceStatus.Rebooting ->
                token.dot.copy(alpha = 0.50f)
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = tween(250),
        label = "cardBorderColor",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("device_card_${device.id}"),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Card Header: Name + Lock + Live Status Pill + Menu
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = token.container,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Dns,
                            contentDescription = null,
                            tint = token.content,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = device.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (device.authRequired) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = stringResource(R.string.cd_auth_required),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LiveStatusPill(status = status, token = token)
                }

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.testTag("device_menu_${device.id}"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = stringResource(R.string.cd_device_options),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_edit)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Edit,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.action_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }

            // Network Telemetry Block (IP:Port and MAC Address in Monospace)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TelemetryItem(
                        label = "ENDPOINT",
                        value = "${device.ip}:${device.port}",
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    TelemetryItem(
                        label = "MAC ADDRESS",
                        value = device.mac,
                        horizontalAlignment = Alignment.End,
                    )
                }
            }

            // Action Controls: Balanced 2x2 Grid (Wake | Sleep, Reboot | Shutdown)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PowerActionButton(
                        label = stringResource(R.string.action_wake),
                        icon = Icons.Rounded.Bolt,
                        containerColor = statusColors.wakeAction.container,
                        contentColor = statusColors.wakeAction.content,
                        onClick = onWake,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("wake_button_${device.id}"),
                    )
                    PowerActionButton(
                        label = stringResource(R.string.action_sleep),
                        icon = Icons.Rounded.Bedtime,
                        containerColor = statusColors.sleepAction.container,
                        contentColor = statusColors.sleepAction.content,
                        onClick = onSleep,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("sleep_button_${device.id}"),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PowerActionButton(
                        label = stringResource(R.string.action_reboot),
                        icon = Icons.Rounded.RestartAlt,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        onClick = onReboot,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("reboot_button_${device.id}"),
                    )
                    PowerActionButton(
                        label = stringResource(R.string.action_shutdown),
                        icon = Icons.Rounded.PowerSettingsNew,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        onClick = onShutdown,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("shutdown_button_${device.id}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveStatusPill(
    status: DeviceStatus,
    token: StatusColorToken,
) {
    val label = when (status) {
        DeviceStatus.Online -> stringResource(R.string.status_online)
        DeviceStatus.Offline -> stringResource(R.string.status_offline)
        DeviceStatus.Checking -> stringResource(R.string.status_checking)
        DeviceStatus.Booting -> stringResource(R.string.status_booting)
        DeviceStatus.Sleeping -> stringResource(R.string.status_sleeping)
        DeviceStatus.ShuttingDown -> stringResource(R.string.status_shutting_down)
        DeviceStatus.Rebooting -> stringResource(R.string.status_rebooting)
    }

    val pulseAlpha = if (status.isTransitioning) {
        val transition = rememberInfiniteTransition(label = "statusPulse")
        val animated by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(650),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "dotAlpha",
        )
        animated
    } else {
        1f
    }

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = token.container,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(pulseAlpha)
                    .clip(CircleShape)
                    .background(token.dot),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = token.content,
            )
        }
    }
}

@Composable
private fun TelemetryItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = MonoFontFamily,
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PowerActionButton(
    label: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.sp,
            ),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

private enum class AgentPlatform {
    Linux,
    Windows,
}

private const val GITHUB_REPO_URL = "https://github.com/Mohammask/LanPower"

@Composable
private fun EmptyDashboardState(
    onAddClick: () -> Unit,
    onCopyCommand: (command: String, confirmationMessage: String) -> Unit,
) {
    var selectedPlatform by remember { mutableStateOf(AgentPlatform.Linux) }
    val commandScrollState = rememberScrollState()

    val linuxCommand = stringResource(R.string.empty_command_linux)
    val windowsCommand = stringResource(R.string.empty_command_windows)
    val linuxCopiedMessage = stringResource(R.string.snackbar_linux_command_copied)
    val windowsCopiedMessage = stringResource(R.string.snackbar_windows_command_copied)

    val activeCommand = when (selectedPlatform) {
        AgentPlatform.Linux -> linuxCommand
        AgentPlatform.Windows -> windowsCommand
    }
    val activeCopiedMessage = when (selectedPlatform) {
        AgentPlatform.Linux -> linuxCopiedMessage
        AgentPlatform.Windows -> windowsCopiedMessage
    }

    LaunchedEffect(selectedPlatform) {
        commandScrollState.scrollTo(0)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 440.dp)
                .padding(bottom = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Illustrated Hero Artwork
            Surface(
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                ),
                tonalElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Image(
                        painter = painterResource(id = R.drawable.img_empty_lan),
                        contentDescription = stringResource(R.string.cd_empty_state_illustration),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        MaterialTheme.colorScheme.background.copy(alpha = 0.35f),
                                    ),
                                ),
                            ),
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.empty_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.empty_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            // Segmented OS Toggle + One-Line Scrollable Command Card
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PlatformSegmentedToggle(
                    selected = selectedPlatform,
                    onSelect = { selectedPlatform = it },
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(commandScrollState),
                        ) {
                            Text(
                                text = activeCommand,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = MonoFontFamily,
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                        IconButton(
                            onClick = { onCopyCommand(activeCommand, activeCopiedMessage) },
                            modifier = Modifier.testTag("copy_setup_command_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = stringResource(R.string.cd_copy_command),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                AnimatedVisibility(visible = selectedPlatform == AgentPlatform.Windows) {
                    Text(
                        text = stringResource(R.string.empty_windows_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("windows_admin_hint"),
                    )
                }
            }

            Button(
                onClick = onAddClick,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("empty_scan_add_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Radar,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.empty_cta),
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            CreatorFooter(modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun PlatformSegmentedToggle(
    selected: AgentPlatform,
    onSelect: (AgentPlatform) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlatformSegmentItem(
                label = stringResource(R.string.os_linux),
                selected = selected == AgentPlatform.Linux,
                onClick = { onSelect(AgentPlatform.Linux) },
                modifier = Modifier.testTag("os_toggle_linux"),
            )
            PlatformSegmentItem(
                label = stringResource(R.string.os_windows),
                selected = selected == AgentPlatform.Windows,
                onClick = { onSelect(AgentPlatform.Windows) },
                modifier = Modifier.testTag("os_toggle_windows"),
            )
        }
    }
}

@Composable
private fun PlatformSegmentItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        animationSpec = tween(180),
        label = "segmentContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(180),
        label = "segmentContent",
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(containerColor)
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun CreatorFooter(
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable {
                runCatching { uriHandler.openUri(GITHUB_REPO_URL) }
            }
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("creator_footer_link"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.footer_made_by),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
    }
}

private fun DeviceStatus.toColorToken(
    colors: LanPowerStatusColors,
): StatusColorToken = when (this) {
    DeviceStatus.Online -> colors.online
    DeviceStatus.Offline -> colors.offline
    DeviceStatus.Checking -> colors.checking
    DeviceStatus.Booting -> colors.booting
    DeviceStatus.Sleeping -> colors.sleeping
    DeviceStatus.ShuttingDown -> colors.shuttingDown
    DeviceStatus.Rebooting -> colors.rebooting
}
