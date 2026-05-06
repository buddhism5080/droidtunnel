package com.anonymous.droidtunnel.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.anonymous.droidtunnel.R
import com.anonymous.droidtunnel.TunnelRuntimeState
import com.anonymous.droidtunnel.TunnelScreenState
import com.anonymous.droidtunnel.TunnelStatus
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunnelScreen(
    state: TunnelScreenState,
    onTokenChanged: (String) -> Unit,
    onConnectClicked: () -> Unit,
    onDisconnectClicked: () -> Unit,
    onEditTokenClicked: () -> Unit,
    onHideTokenEditorClicked: () -> Unit,
    onRequestBatteryOptimizationClicked: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(id = R.string.app_name),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(id = R.string.app_subtitle),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                ControlCard(
                    runtime = state.runtime,
                    hasToken = state.token.isNotBlank(),
                    onConnectClicked = onConnectClicked,
                    onDisconnectClicked = onDisconnectClicked,
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                StatusOverviewCard(runtime = state.runtime)
            }

            item {
                TokenCard(
                    token = state.token,
                    showTokenEditor = state.showTokenEditor,
                    onTokenChanged = onTokenChanged,
                    onEditTokenClicked = onEditTokenClicked,
                    onHideTokenEditorClicked = onHideTokenEditorClicked,
                )
            }

            item {
                ProtectionCard(
                    batteryOptimizationRecommended = state.batteryOptimizationRecommended,
                    onRequestBatteryOptimizationClicked = onRequestBatteryOptimizationClicked,
                )
            }

            item {
                LogsCard(
                    modifier = Modifier.height(240.dp),
                    logs = state.runtime.logs,
                )
            }
        }
    }
}

@Composable
private fun StatusOverviewCard(runtime: TunnelRuntimeState) {
    val (label, color) = statusPresentation(runtime.status)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(id = R.string.status_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = runtime.statusMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(label) },
                    colors = AssistChipDefaults.assistChipColors(
                        disabledContainerColor = color.copy(alpha = 0.18f),
                        disabledLabelColor = color,
                    ),
                    border = null,
                )
            }

            if (runtime.status == TunnelStatus.STARTING || runtime.status == TunnelStatus.RESTARTING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatTile(
                    modifier = Modifier.weight(1f),
                    title = stringResource(id = R.string.stat_ready_connections),
                    value = runtime.readyConnections.toString(),
                )
                StatTile(
                    modifier = Modifier.weight(1f),
                    title = stringResource(id = R.string.stat_restart_count),
                    value = runtime.restartCount.toString(),
                )
                StatTile(
                    modifier = Modifier.weight(1f),
                    title = stringResource(id = R.string.stat_probe_failures),
                    value = runtime.consecutiveProbeFailures.toString(),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow(
                    label = stringResource(id = R.string.info_last_healthy),
                    value = formatTimestamp(runtime.lastHealthyAtMillis),
                )
                InfoRow(
                    label = stringResource(id = R.string.info_next_restart),
                    value = formatTimestamp(runtime.nextRestartAtMillis),
                )
                InfoRow(
                    label = stringResource(id = R.string.info_binary_path),
                    value = runtime.binaryPath.ifBlank { stringResource(id = R.string.info_binary_unknown) },
                )
                if (runtime.lastExitReason.isNotBlank()) {
                    InfoRow(
                        label = stringResource(id = R.string.info_last_exit_reason),
                        value = runtime.lastExitReason,
                    )
                }
            }
        }
    }
}

@Composable
private fun TokenCard(
    token: String,
    showTokenEditor: Boolean,
    onTokenChanged: (String) -> Unit,
    onEditTokenClicked: () -> Unit,
    onHideTokenEditorClicked: () -> Unit,
) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(id = R.string.token_card_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (!showTokenEditor && token.isNotBlank()) {
                Text(
                    text = stringResource(id = R.string.token_saved_summary, token.length),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onEditTokenClicked) {
                        Text(text = stringResource(id = R.string.edit_token))
                    }
                }
            }

            AnimatedVisibility(visible = showTokenEditor || token.isBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = token,
                        onValueChange = onTokenChanged,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 8,
                        label = { Text(text = stringResource(id = R.string.token_hint)) },
                        supportingText = {
                            Text(text = stringResource(id = R.string.token_supporting))
                        },
                    )
                    if (token.isNotBlank()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = onHideTokenEditorClicked) {
                                Text(text = stringResource(id = R.string.hide_editor))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlCard(
    runtime: TunnelRuntimeState,
    hasToken: Boolean,
    onConnectClicked: () -> Unit,
    onDisconnectClicked: () -> Unit,
) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(id = R.string.control_card_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (runtime.desiredRunning) {
                    stringResource(id = R.string.control_running_hint)
                } else {
                    stringResource(id = R.string.control_stopped_hint)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onConnectClicked,
                    modifier = Modifier.weight(1f),
                    enabled = hasToken,
                ) {
                    Text(
                        text = if (runtime.desiredRunning) {
                            stringResource(id = R.string.reconnect)
                        } else {
                            stringResource(id = R.string.connect)
                        },
                    )
                }
                OutlinedButton(
                    onClick = onDisconnectClicked,
                    modifier = Modifier.weight(1f),
                    enabled = runtime.desiredRunning || runtime.status != TunnelStatus.STOPPED,
                ) {
                    Text(text = stringResource(id = R.string.disconnect))
                }
            }
        }
    }
}

@Composable
private fun ProtectionCard(
    batteryOptimizationRecommended: Boolean,
    onRequestBatteryOptimizationClicked: () -> Unit,
) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(id = R.string.protection_card_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (batteryOptimizationRecommended) {
                    stringResource(id = R.string.protection_recommended)
                } else {
                    stringResource(id = R.string.protection_enabled)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onRequestBatteryOptimizationClicked) {
                Text(text = stringResource(id = R.string.request_battery_optimization))
            }
        }
    }
}

@Composable
private fun LogsCard(
    modifier: Modifier = Modifier,
    logs: List<String>,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(id = R.string.log_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(id = R.string.log_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(logs) { index, line ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (index != logs.lastIndex) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 16.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun statusPresentation(status: TunnelStatus): Pair<String, Color> = when (status) {
    TunnelStatus.STOPPED -> "已停止" to Color(0xFF64748B)
    TunnelStatus.STARTING -> "启动中" to Color(0xFF2563EB)
    TunnelStatus.HEALTHY -> "健康" to Color(0xFF16A34A)
    TunnelStatus.DEGRADED -> "异常" to Color(0xFFF59E0B)
    TunnelStatus.RESTARTING -> "重连中" to Color(0xFF9333EA)
    TunnelStatus.ERROR -> "错误" to Color(0xFFDC2626)
}

private fun formatTimestamp(timestamp: Long?): String {
    if (timestamp == null || timestamp <= 0L) {
        return "--"
    }
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(timestamp))
}
