package io.ahmed.sysmon.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ahmed.sysmon.data.entity.DeviceEntity
import io.ahmed.sysmon.repo.Repository
import io.ahmed.sysmon.service.RouterPollService
import io.ahmed.sysmon.util.Format
import io.ahmed.sysmon.util.rememberToday
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(onOpenDevice: (String) -> Unit = {}) {
    val context = LocalContext.current
    val repo = remember { Repository(context) }
    val scope = rememberCoroutineScope()

    val devices by repo.devices.observeAll().collectAsStateWithLifecycle(emptyList())

    // rememberToday() flips at midnight — flows re-subscribe with fresh dayPrefix.
    val today = rememberToday()
    val todayKey = remember(today) { today.toString() }
    val perDevice by repo.deviceUsage.todaysPerDeviceFlow(todayKey)
        .collectAsStateWithLifecycle(emptyList())
    val usageByMac = perDevice.associate { it.mac to it.total }

    val online = devices.count { it.isOnline == 1 }

    var isRefreshing by remember { mutableStateOf(false) }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            scope.launch {
                isRefreshing = true
                RouterPollService.requestRefresh()
                delay(1200)
                isRefreshing = false
            }
        },
        modifier = Modifier.fillMaxSize()
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Devices", style = MaterialTheme.typography.headlineMedium,
                 fontWeight = FontWeight.Bold)
            Text("$online online · ${devices.size} total",
                 style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                "Per-device totals are estimated from Wi-Fi activity — the router doesn't " +
                    "expose exact per-host counters. Wired devices (TV, PC, console) aren't " +
                    "tracked individually, so their traffic is spread across the Wi-Fi devices.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            if (devices.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Text(
                        "No devices yet. Discovery runs on every poll — pull down to refresh or check back in ~60 s.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // Sort: online first, then by today usage desc
                val sorted = devices.sortedWith(
                    compareByDescending<DeviceEntity> { it.isOnline }
                        .thenByDescending { usageByMac[it.mac] ?: 0.0 }
                )
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(sorted, key = { it.mac }) { d ->
                        DeviceCard(
                            d,
                            todayMb = usageByMac[d.mac],
                            onClick = { onOpenDevice(d.mac) },
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(200),
                                placementSpec = tween(250),
                                fadeOutSpec = tween(160)
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    d: DeviceEntity,
    todayMb: Double?,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val dot = if (d.isOnline == 1) Color(0xFF2CA02C) else Color(0xFF888888)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .animateContentSize(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Filled.Router, null,
                 tint = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.size(28.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.hostname?.takeIf { it.isNotBlank() } ?: "(unknown)",
                         style = MaterialTheme.typography.titleSmall,
                         fontWeight = FontWeight.SemiBold,
                         modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dot)
                    )
                }
                Text("IP ${d.lastIp ?: "—"}",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("MAC ${d.mac}",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("last seen ${Format.ago(d.lastSeen)}",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("today",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Format.mb(todayMb),
                     style = MaterialTheme.typography.titleMedium,
                     fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
