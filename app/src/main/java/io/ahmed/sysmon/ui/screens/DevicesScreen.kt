package io.ahmed.sysmon.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import io.ahmed.sysmon.data.entity.AlertEntity
import io.ahmed.sysmon.data.entity.BlockScheduleEntity
import io.ahmed.sysmon.data.entity.DeviceEntity
import io.ahmed.sysmon.repo.Repository
import io.ahmed.sysmon.service.RouterPollService
import io.ahmed.sysmon.service.ScheduleEnforcer
import io.ahmed.sysmon.service.router.RouterAdapters
import io.ahmed.sysmon.service.router.RouterKind
import io.ahmed.sysmon.ui.components.DeviceActionBinding
import io.ahmed.sysmon.ui.components.DeviceEditSheet
import io.ahmed.sysmon.ui.components.DeviceIconKind
import io.ahmed.sysmon.util.Format
import io.ahmed.sysmon.util.Logger
import io.ahmed.sysmon.util.WifiNetworkBinder
import io.ahmed.sysmon.util.rememberToday
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DevicesScreen(onOpenDevice: (String) -> Unit = {}) {
    val context = LocalContext.current
    val repo = remember { Repository(context) }
    val scope = rememberCoroutineScope()

    val devices by repo.devices.observeAll().collectAsStateWithLifecycle(emptyList())

    val today = rememberToday()
    val todayKey = remember(today) { today.toString() }
    val perDevice by repo.deviceUsage.todaysPerDeviceFlow(todayKey)
        .collectAsStateWithLifecycle(emptyList())
    val gapMbToday by repo.deviceUsage.todaysGapMbFlow(todayKey)
        .collectAsStateWithLifecycle(0.0)
    val usageByMac = perDevice.associate { it.mac to it.total }

    val online = devices.count { it.isOnline == 1 }

    var isRefreshing by remember { mutableStateOf(false) }
    var groupView by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<DeviceEntity?>(null) }

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Devices", style = MaterialTheme.typography.headlineMedium,
                         fontWeight = FontWeight.Bold)
                    Text("$online online · ${devices.size} total",
                         style = MaterialTheme.typography.labelMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilterChip(
                    selected = groupView,
                    onClick = { groupView = !groupView },
                    label = { Text(if (groupView) "Grouped" else "Flat") }
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Long-press a device to rename it, set a budget, or pick an icon. " +
                    "Per-device totals are Wi-Fi estimates — wired traffic isn't tracked per host.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (gapMbToday > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Offline window today: ${Format.mb(gapMbToday)} not attributed to any device.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Spacer(Modifier.height(12.dp))

            if (devices.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Text(
                        "No devices yet. Discovery runs on every poll — pull down to refresh.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val sorted = devices.sortedWith(
                    compareByDescending<DeviceEntity> { it.isOnline }
                        .thenByDescending { usageByMac[it.mac] ?: 0.0 }
                )
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    if (groupView) {
                        val grouped = sorted.groupBy { it.group?.takeIf { g -> g.isNotBlank() } ?: "Ungrouped" }
                            .toSortedMap(compareBy { if (it == "Ungrouped") "zzz" else it })
                        for ((g, items) in grouped) {
                            item(key = "hdr_$g") {
                                Text(
                                    "$g · ${items.size}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                                )
                            }
                            items(items, key = { it.mac }) { d ->
                                DeviceCard(
                                    d,
                                    todayMb = usageByMac[d.mac],
                                    onClick = { onOpenDevice(d.mac) },
                                    onLongClick = { editing = d },
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = tween(200),
                                        placementSpec = tween(250),
                                        fadeOutSpec = tween(160)
                                    )
                                )
                            }
                        }
                    } else {
                        items(sorted, key = { it.mac }) { d ->
                            DeviceCard(
                                d,
                                todayMb = usageByMac[d.mac],
                                onClick = { onOpenDevice(d.mac) },
                                onLongClick = { editing = d },
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

    editing?.let { dev ->
        val selfMac = repo.prefs.selfMac
        val actions = DeviceActionBinding(
            isSelfMac = selfMac.isNotBlank() && selfMac.equals(dev.mac, ignoreCase = true),
            onBlock = { blocked ->
                withContext(Dispatchers.IO) {
                    val adapter = RouterAdapters.forKind(
                        kind = RouterKind.fromPref(repo.prefs.routerKind),
                        baseUrl = repo.prefs.routerBase,
                        username = repo.prefs.routerUser,
                        password = repo.prefs.routerPassword,
                        cachedCookie = repo.prefs.sessionCookie.takeIf { it.isNotBlank() },
                        onSessionEstablished = { sid -> repo.prefs.sessionCookie = sid },
                        trace = { line -> Logger.i(context, "action", line) }
                    )
                    WifiNetworkBinder.withWifiProcess(context) { adapter.blockMac(dev.mac, blocked) }
                        ?: adapter.blockMac(dev.mac, blocked)
                    repo.insertAlert(AlertEntity(
                        ts = nowIso(),
                        reason = "ACTION",
                        valueMb = 0.0,
                        sentOk = 1,
                        error = "${if (blocked) "block" else "unblock"} ${dev.mac}"
                    ))
                }
            },
            onApplyQos = { down, up ->
                withContext(Dispatchers.IO) {
                    val adapter = RouterAdapters.forKind(
                        kind = RouterKind.fromPref(repo.prefs.routerKind),
                        baseUrl = repo.prefs.routerBase,
                        username = repo.prefs.routerUser,
                        password = repo.prefs.routerPassword,
                        cachedCookie = repo.prefs.sessionCookie.takeIf { it.isNotBlank() },
                        onSessionEstablished = { sid -> repo.prefs.sessionCookie = sid },
                        trace = { line -> Logger.i(context, "action", line) }
                    )
                    WifiNetworkBinder.withWifiProcess(context) {
                        adapter.setBandwidthLimitKbps(dev.mac, down, up)
                    } ?: adapter.setBandwidthLimitKbps(dev.mac, down, up)
                    repo.insertAlert(AlertEntity(
                        ts = nowIso(),
                        reason = "ACTION",
                        valueMb = 0.0,
                        sentOk = 1,
                        error = "qos ${dev.mac} ↓$down ↑$up kbps"
                    ))
                }
            },
            onAddSchedule = { daysMask, startMin, endMin ->
                withContext(Dispatchers.IO) {
                    repo.schedules.insert(BlockScheduleEntity(
                        mac = dev.mac,
                        daysOfWeekMask = daysMask,
                        startMinuteOfDay = startMin,
                        endMinuteOfDay = endMin,
                        enabled = 1,
                        note = "per-device"
                    ))
                }
                ScheduleEnforcer.rescheduleAll(context)
            }
        )

        DeviceEditSheet(
            device = dev,
            actions = actions,
            onDismiss = { editing = null },
            onSave = { label, group, iconKind, dailyBudgetMb, monthlyBudgetMb ->
                withContext(Dispatchers.IO) {
                    repo.saveDeviceEdits(dev.mac, label, group, iconKind, dailyBudgetMb, monthlyBudgetMb)
                }
            }
        )
    }
}

private fun nowIso(): String =
    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceCard(
    d: DeviceEntity,
    todayMb: Double?,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val dot = if (d.isOnline == 1) Color(0xFF2CA02C) else Color(0xFF888888)
    val displayName = d.label?.takeIf { it.isNotBlank() }
        ?: d.hostname?.takeIf { it.isNotBlank() }
        ?: "(unknown)"
    val kind = DeviceIconKind.fromKeyOrDefault(d.iconKind)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
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
            Icon(kind.icon, null,
                 tint = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.size(28.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(displayName,
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
                if (!d.group.isNullOrBlank()) {
                    Text(d.group,
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.primary)
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
                d.dailyBudgetMb?.let { cap ->
                    val used = todayMb ?: 0.0
                    val pct = (used / cap).coerceAtMost(1.0).toFloat()
                    val over = used > cap
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { pct },
                        color = if (over) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.fillMaxWidth().height(4.dp)
                    )
                    Text(
                        "${Format.mb(used)} / $cap MB daily",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (over) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
