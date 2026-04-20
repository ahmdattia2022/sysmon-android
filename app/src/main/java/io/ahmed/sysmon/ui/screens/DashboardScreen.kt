package io.ahmed.sysmon.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ahmed.sysmon.data.entity.AlertEntity
import io.ahmed.sysmon.data.entity.UsageSampleEntity
import io.ahmed.sysmon.repo.Repository
import io.ahmed.sysmon.service.Notifier
import io.ahmed.sysmon.service.RouterPollService
import io.ahmed.sysmon.util.Format
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val repo = remember { Repository(context) }
    val scope = rememberCoroutineScope()

    // Reactive sources — Flow from Room, push updates the instant a poll lands
    val recentSamples by repo.usage.recent(3).collectAsStateWithLifecycle(emptyList())
    val recentAlerts by repo.alerts.recent(5).collectAsStateWithLifecycle(emptyList())

    val today = remember { LocalDate.now().toString() }
    val weekAgo = remember {
        LocalDateTime.now().minusDays(7)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
    }
    val routerTodayMb by repo.usage.sumMbOnDayFlow("router_wan", today)
        .collectAsStateWithLifecycle(0.0)
    val weekSumMb by repo.usage.sumMbSinceFlow("router_wan", weekAgo)
        .collectAsStateWithLifecycle(0.0)
    val avgDailyMb = weekSumMb / 7.0
    val sampleCount by repo.usage.countFlow("router_wan")
        .collectAsStateWithLifecycle(0)
    val allDevices by repo.devices.observeAll().collectAsStateWithLifecycle(emptyList())
    val onlineDevices = allDevices.count { it.isOnline == 1 }
    val totalDevices = allDevices.size

    // Bundle cap is only edited from Settings, so read once + refresh on resume
    // (keyed to sampleCount so the dashboard also re-reads once the first poll
    // lands — avoids a pointless 10s timer looping while the screen is off).
    var bundleGb by remember { mutableIntStateOf(repo.prefs.bundleGb) }
    LaunchedEffect(sampleCount) { bundleGb = repo.prefs.bundleGb }

    // Re-compute bundle balance whenever a new poll lands OR the user adds / edits
    // a cycle anchor in Settings. `cyclesList` is observed as a Flow so inserts
    // propagate here within one Room dispatcher tick.
    val cyclesList by repo.cycles.observeAll().collectAsStateWithLifecycle(emptyList())
    var bundleBalance by remember { mutableStateOf<Repository.BundleBalance?>(null) }
    LaunchedEffect(sampleCount, cyclesList.size, cyclesList.firstOrNull()?.id) {
        bundleBalance = repo.bundleBalance()
    }

    // Offline-gap catch-up: if the poll service backfilled samples in the last 6h,
    // surface an amber chip so the user knows the spike isn't real-time traffic.
    val sixHoursAgo = remember(sampleCount) {
        LocalDateTime.now().minusHours(6).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
    }
    val backfilledMb by repo.usage.sumBackfilledMbSinceFlow("router_wan", sixHoursAgo)
        .collectAsStateWithLifecycle(0.0)
    val backfilledCount by repo.usage.backfilledCountSinceFlow("router_wan", sixHoursAgo)
        .collectAsStateWithLifecycle(0)
    // `tick` drove StatusCard's "Last poll · Xs ago" string refresh. Keep a low-
    // frequency tick so the relative time stays current without spamming CPU.
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) { delay(30_000); tick++ }
    }

    val latestSample: UsageSampleEntity? = recentSamples.firstOrNull { it.source == "router_wan" }
    val lastFail = recentAlerts.firstOrNull { it.reason == "ROUTER_POLL_FAIL" }
    val healthy = latestSample != null &&
        latestSample.ts > (lastFail?.ts ?: "")
    val firstLaunch = sampleCount == 0

    var isRefreshing by remember { mutableStateOf(false) }
    val pullState = rememberPullToRefreshState()

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            scope.launch {
                isRefreshing = true
                RouterPollService.requestRefresh()
                // Keep the spinner visible at least 800ms so the animation reads
                delay(1200)
                isRefreshing = false
            }
        },
        state = pullState,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Column {
                Text(
                    "Today",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    LocalDate.now().toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            StatusCard(
                healthy = healthy,
                latestSample = latestSample,
                lastFail = lastFail,
                tick = tick
            )

            AnimatedVisibility(
                visible = backfilledCount > 0 && backfilledMb > 0,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                BackfillChip(mb = backfilledMb, minutes = backfilledCount)
            }

            if (firstLaunch) {
                SkeletonHero()
            } else {
                val cycle = bundleBalance
                if (cycle != null) {
                    BundleHeroCard(
                        balance = cycle,
                        todayMb = routerTodayMb,
                        avgMbPerDay = avgDailyMb
                    )
                } else {
                    HeroUsageCard(
                        todayMb = routerTodayMb,
                        avgMbPerDay = avgDailyMb,
                        bundleGb = bundleGb,
                        sampleCount = sampleCount
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MiniStatCard(
                    modifier = Modifier.weight(1f),
                    label = "Devices online",
                    value = "$onlineDevices",
                    sub = "of $totalDevices total"
                )
                MiniStatCard(
                    modifier = Modifier.weight(1f),
                    label = "Polls in DB",
                    value = "$sampleCount",
                    sub = "router samples"
                )
            }

            Text(
                "Recent alerts",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.animateContentSize(animationSpec = tween(200))
            ) {
                if (recentAlerts.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Text(
                            "No alerts yet.",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    for (a in recentAlerts) {
                        key(a.id) {
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn() + slideInVertically { it / 2 },
                                exit = fadeOut() + slideOutVertically { -it / 2 }
                            ) { AlertRow(a) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            RouterPollService.requestRefresh()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Refresh now")
                }
                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            val ts = LocalDateTime.now().format(
                                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                            )
                            repo.insertAlert(AlertEntity(
                                ts = ts, reason = "TEST", valueMb = 42.0,
                                sentOk = 1, error = "manual test"
                            ))
                            Notifier.fire(context, "TEST", 42.0, "manual test")
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.NotificationsActive, null,
                         modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Test alert")
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    healthy: Boolean,
    latestSample: UsageSampleEntity?,
    lastFail: AlertEntity?,
    @Suppress("UNUSED_PARAMETER") tick: Long
) {
    val container = if (healthy)
        MaterialTheme.colorScheme.secondaryContainer
    else
        MaterialTheme.colorScheme.errorContainer
    val onContainer = if (healthy)
        MaterialTheme.colorScheme.onSecondaryContainer
    else
        MaterialTheme.colorScheme.onErrorContainer

    val icon: ImageVector = if (healthy) Icons.Filled.CheckCircle else Icons.Filled.Error
    val title = if (latestSample == null) "Awaiting first poll"
        else if (healthy) "Router reachable" else "Router unreachable"
    val subtitle = when {
        latestSample == null -> "The poller starts automatically"
        healthy -> "Last poll · ${Format.ago(latestSample.ts)}"
        else -> friendlyError(lastFail?.error)
    }

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val pulse by rememberInfiniteTransition("pulse").animateFloat(
                initialValue = 0.7f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(900, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ), label = "pulse"
            )
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        if (healthy) Color(0xFF2CA02C).copy(alpha = pulse)
                        else Color(0xFFD62728).copy(alpha = pulse)
                    )
            )
            Column(modifier = Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = title,
                    transitionSpec = {
                        (fadeIn(tween(160)) + slideInVertically { it / 3 }) togetherWith
                            (fadeOut(tween(120)) + slideOutVertically { -it / 3 })
                    },
                    label = "status-title"
                ) { t ->
                    Text(t, style = MaterialTheme.typography.titleMedium,
                         fontWeight = FontWeight.SemiBold, color = onContainer)
                }
                AnimatedContent(
                    targetState = subtitle,
                    transitionSpec = {
                        fadeIn(tween(160)) togetherWith fadeOut(tween(120))
                    },
                    label = "status-sub"
                ) { s ->
                    Text(s, style = MaterialTheme.typography.bodySmall,
                         color = onContainer.copy(alpha = 0.8f))
                }
            }
            Icon(icon, null, tint = onContainer)
        }
    }
}

@Composable
private fun HeroUsageCard(
    todayMb: Double,
    avgMbPerDay: Double,
    bundleGb: Int,
    sampleCount: Int
) {
    val hasEnough = sampleCount >= 12 && avgMbPerDay > 0
    val daysLeft = if (hasEnough) (bundleGb / (avgMbPerDay / 1024.0)).toInt() else null

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Household usage today",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            // Animated number: crossfades between values on every Flow emission
            AnimatedContent(
                targetState = Format.mb(todayMb),
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically { it / 4 }) togetherWith
                        (fadeOut(tween(160)) + slideOutVertically { -it / 4 })
                },
                label = "today-mb"
            ) { txt ->
                Text(
                    txt,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Text(
                "$sampleCount polls · ${Format.gb(todayMb, 3)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f)
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Bundle", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    Text("$bundleGb GB", style = MaterialTheme.typography.titleMedium,
                         color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Daily avg", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    AnimatedContent(
                        targetState = if (hasEnough) Format.gb(avgDailyMbForDisplay(avgMbPerDay), 2) else "—",
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "avg"
                    ) { v ->
                        Text(v, style = MaterialTheme.typography.titleMedium,
                             color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Runway", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    AnimatedContent(
                        targetState = if (daysLeft == null) "—" else "~$daysLeft d",
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "runway"
                    ) { v ->
                        Text(v, style = MaterialTheme.typography.titleMedium,
                             color = MaterialTheme.colorScheme.onPrimaryContainer,
                             fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            AnimatedVisibility(
                visible = !hasEnough,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Text(
                    "Runway calibrates after ~12 samples (≈12 min of polling).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

private fun avgDailyMbForDisplay(mb: Double): Double = mb

/**
 * Amber chip shown when the poll service recently backfilled an offline window.
 * Explains that the bars ahead of it in the hourly chart come from a single
 * catch-up poll, not minute-by-minute real-time data.
 */
@Composable
private fun BackfillChip(mb: Double, minutes: Int) {
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Filled.CloudSync, null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Caught up ${Format.mb(mb)} from an offline window",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    "$minutes min of Wi-Fi-off traffic spread evenly across the gap",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

/**
 * Hero card driven by the active BundleCycleEntity + WAN usage since its start.
 * Shows remaining GB prominently, days elapsed / days until exhausted at current pace,
 * and a progress bar for used vs total.
 */
@Composable
private fun BundleHeroCard(
    balance: Repository.BundleBalance,
    todayMb: Double,
    avgMbPerDay: Double
) {
    val anchor = balance.anchor
    val daysElapsed = runCatching {
        val start = java.time.LocalDateTime.parse(anchor.startDateIso)
        java.time.Duration.between(start, java.time.LocalDateTime.now())
            .toDays().coerceAtLeast(0L).toInt()
    }.getOrDefault(0)
    val remainingGb = balance.remainingGb
    val paceMbPerDay = avgMbPerDay.coerceAtLeast(1.0)
    val daysLeftAtPace = (remainingGb * 1024.0 / paceMbPerDay).toInt().coerceAtLeast(0)
    val fraction = if (balance.totalGb > 0) (balance.usedGb / balance.totalGb)
        .coerceIn(0.0, 1.0).toFloat() else 0f

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Bundle balance",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            AnimatedContent(
                targetState = "%.1f GB".format(remainingGb),
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically { it / 4 }) togetherWith
                        (fadeOut(tween(160)) + slideOutVertically { -it / 4 })
                },
                label = "bundle-remaining"
            ) { txt ->
                Text(
                    txt,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Text(
                "of ${"%.0f".format(balance.totalGb)} GB · ${anchor.kind.toHumanLabel()}" +
                    (anchor.provider?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(10.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.18f)
            )
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f)
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Today", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    Text(Format.mb(todayMb),
                         style = MaterialTheme.typography.titleMedium,
                         color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Cycle day", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    Text("day ${daysElapsed + 1}",
                         style = MaterialTheme.typography.titleMedium,
                         color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Runway", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    AnimatedContent(
                        targetState = "~$daysLeftAtPace d",
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "runway-cycle"
                    ) { v ->
                        Text(v, style = MaterialTheme.typography.titleMedium,
                             color = MaterialTheme.colorScheme.onPrimaryContainer,
                             fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

private fun String.toHumanLabel(): String = when (this) {
    io.ahmed.sysmon.data.entity.BundleCycleEntity.KIND_MANUAL_FRESH -> "fresh recharge"
    io.ahmed.sysmon.data.entity.BundleCycleEntity.KIND_MANUAL_TOPUP -> "mid-cycle top-up"
    io.ahmed.sysmon.data.entity.BundleCycleEntity.KIND_SMS_AUTO -> "from SMS"
    io.ahmed.sysmon.data.entity.BundleCycleEntity.KIND_MANUAL_REMAINING -> "balance checkpoint"
    else -> this.lowercase()
}

// Map exception signatures to human-friendly subtitles for the status card.
// The raw signature still lives in the Alerts tab for debugging.
private fun friendlyError(raw: String?): String {
    if (raw.isNullOrBlank()) return "Last error unknown"
    val lower = raw.lowercase()
    return when {
        "timeout" in lower || "timed out" in lower ->
            "Router didn't respond — check Wi-Fi"
        "ehostunreach" in lower || "unreachable" in lower ->
            "Can't reach router — VPN blocking LAN?"
        "connect" in lower || "refused" in lower ->
            "Connection refused — is the router up?"
        "session" in lower || "login" in lower ->
            "Session expired — re-authenticating"
        else -> raw.take(80)
    }
}

@Composable
private fun SkeletonHero() {
    val alpha by rememberInfiniteTransition("skeleton").animateFloat(
        initialValue = 0.35f, targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "skeleton-alpha"
    )
    val base = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = alpha)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = base)
    ) {
        Column(modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier
                .height(14.dp)
                .fillMaxWidth(0.5f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)))
            Box(modifier = Modifier
                .height(36.dp)
                .fillMaxWidth(0.35f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)))
            Box(modifier = Modifier
                .height(10.dp)
                .fillMaxWidth(0.6f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)))
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(3) {
                    Box(modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)))
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "Collecting first samples…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MiniStatCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    sub: String
) {
    Card(
        modifier = modifier.animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            AnimatedContent(
                targetState = value,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "mini-$label"
            ) { v ->
                Text(v, style = MaterialTheme.typography.headlineSmall,
                     fontWeight = FontWeight.SemiBold)
            }
            Text(sub, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AlertRow(a: AlertEntity) {
    val (bg, fg, icon) = when (a.reason) {
        "ROUTER_POLL_FAIL" -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Filled.Error
        )
        "TEST" -> Triple(
            MaterialTheme.colorScheme.surfaceContainerLow,
            MaterialTheme.colorScheme.onSurface,
            Icons.Filled.Sync
        )
        else -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            Icons.Filled.NotificationsActive
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(a.reason,
                     style = MaterialTheme.typography.bodyMedium,
                     fontWeight = FontWeight.SemiBold,
                     color = fg)
                Text(Format.ago(a.ts),
                     style = MaterialTheme.typography.labelSmall,
                     color = fg.copy(alpha = 0.7f))
            }
            if (a.valueMb > 0) {
                Text(Format.mb(a.valueMb),
                     style = MaterialTheme.typography.bodyMedium,
                     fontWeight = FontWeight.Medium,
                     color = fg)
            }
        }
    }
}
