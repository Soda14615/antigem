package ai.antigem.ui.components

import ai.antigem.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.remote.AgyActiveInstance
import ai.antigem.data.remote.BridgeConnectionState
import ai.antigem.theme.ClaudeTerracotta

@Composable
fun ConnectionStatusBadge(
    state: BridgeConnectionState,
    bridgeUrl: String,
    activeInstances: List<AgyActiveInstance>,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }

    val (dotColor, labelText) = when (state) {
        BridgeConnectionState.CONNECTED_READY -> Pair(ai.antigem.theme.AppColors.success, "Bridge Ready")
        BridgeConnectionState.STREAMING -> Pair(ai.antigem.theme.AppColors.success, "Generating...")
        BridgeConnectionState.SPAWNING_INSTANCE -> Pair(ai.antigem.theme.AppColors.warning, "Warming Instance...")
        BridgeConnectionState.CONNECTING -> Pair(ai.antigem.theme.AppColors.info, "Connecting...")
        BridgeConnectionState.RECONNECTING -> Pair(ai.antigem.theme.AppColors.info, "Reconnecting...")
        BridgeConnectionState.OFFLINE_ERROR -> Pair(ai.antigem.theme.AppColors.danger, "Bridge Offline")
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable { showDialog = true }
            .padding(horizontal = 6.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .background(dotColor, CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape)
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(dotColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.connection_status_badge_bridge_status), style = MaterialTheme.typography.titleMedium)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.connection_status_badge_status, labelText.toString()),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.connection_status_badge_endpoint, bridgeUrl.toString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = stringResource(R.string.connection_status_badge_active_worker_instances, activeInstances.size.toString()),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    if (activeInstances.isEmpty()) {
                        Text(
                            text = stringResource(R.string.connection_status_badge_no_active_warm_processes_in_ram),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        activeInstances.forEach { inst ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = inst.model,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 12.5.sp,
                                            color = ClaudeTerracotta
                                        )
                                        Text(
                                            text = stringResource(R.string.connection_status_badge_pid, inst.pid.toString()),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(R.string.connection_status_badge_chat_uptime_s, (inst.conversationId.take(12)).toString(), inst.uptimeSeconds.toString()),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDialog = false
                        onReconnect()
                    }
                ) {
                    Icon(imageVector = Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.chat_screen_reconnect))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.accounts_ui_close))
                }
            }
        )
    }
}
