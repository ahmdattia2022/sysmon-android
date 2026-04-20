package io.ahmed.sysmon.ui.screens

import android.graphics.Color as AndroidColor
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import io.ahmed.sysmon.data.dao.DeviceUsageDao
import io.ahmed.sysmon.data.entity.DeviceEntity
import io.ahmed.sysmon.repo.Repository
import io.ahmed.sysmon.service.RouterPollService
import io.ahmed.sysmon.util.Format
import io.ahmed.sysmon.util.rememberToday
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceProfileScreen(mac: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { Repository(context) }
    val scope = rememberCoroutineScope()

    var device by remember { mutableStateOf<DeviceEntity?>(null) }
    LaunchedEffect(mac) {
        device = repo.devices.byMac(mac)
    }

    // rememberToday() flips at midnight — every keyed `remember(today)` below
    // recomputes so the SQL date arguments stay in sync with wall-clock.
    val today = rememberToday()
    val todayKey = remember(today) { today.toString() }
    val sinceLast30 = remember(today) {
        today.minusDays(29).atStartOfDay()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
    }
    val sinceLast12mo = remember(today) {
        today.minusMonths(11).withDayOfMonth(1).atStartOfDay()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
    }

    val todayMb by repo.deviceUsage.mbOnDayFlow(mac, todayKey)
        .collectAsStateWithLifecycle(0.0)
    val daily by repo.deviceUsage.dailyForDevice(mac, sinceLast30)
        .collectAsStateWithLifecycle(emptyList())
    val monthly by repo.deviceUsage.monthlyForDevice(mac, sinceLast12mo)
        .collectAsStateWithLifecycle(emptyList())
    val hourly by repo.deviceUsage.hourlyForDeviceOnDay(mac, todayKey)
        .collectAsStateWithLifecycle(emptyList())

    var isRefreshing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(device?.hostname?.takeIf { it.isNotBlank() } ?: "(unknown device)",
                         maxLines = 1, fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { pad ->
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
            modifier = Modifier.fillMaxSize().padding(pad)
        ) {
        Column(modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            DeviceHeader(device)

            // Today card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Today",
                         style = MaterialTheme.typography.labelLarge,
                         color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    AnimatedContent(
                        targetState = Format.mb(todayMb),
                        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                        label = "dev-today"
                    ) { v ->
                        Text(v, style = MaterialTheme.typography.displaySmall,
                             fontWeight = FontWeight.Bold,
                             color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text(Format.gb(todayMb, 3),
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                }
            }

            SectionTitle("Hourly (today)")
            BarCard(
                entries = buildHourlyEntries(hourly),
                labels = (0..23).map { "%02d".format(it) },
                height = 180,
                color = AndroidColor.rgb(31, 119, 180)
            )

            SectionTitle("Last 30 days")
            BarCard(
                entries = buildDailyEntries(daily),
                labels = buildDailyLabels(),
                height = 220,
                color = AndroidColor.rgb(44, 160, 44)
            )

            SectionTitle("Last 12 months")
            BarCard(
                entries = buildMonthlyEntries(monthly),
                labels = buildMonthlyLabels(),
                height = 220,
                color = AndroidColor.rgb(255, 127, 14)
            )

            // Monthly table (MB / GB)
            SectionTitle("Monthly totals")
            val currentMonthKey = remember(today) {
                "%04d-%02d".format(today.year, today.monthValue)
            }
            if (monthly.isEmpty()) {
                Text("No data yet — will populate as the device generates traffic.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        for (m in monthly.reversed()) {
                            val isCurrent = m.month == currentMonthKey
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text(m.month, fontWeight = FontWeight.Medium)
                                    if (isCurrent) {
                                        val dayWord = if (m.dayCount == 1) "day" else "days"
                                        Text("month-to-date · ${m.dayCount} $dayWord tracked",
                                             style = MaterialTheme.typography.labelSmall,
                                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Text(Format.mb(m.total),
                                     fontWeight = FontWeight.SemiBold)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
                Text(
                    "Past months show complete totals. The current month is month-to-date " +
                        "and grows each day the device is tracked.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        }
    }
}

@Composable
private fun DeviceHeader(d: DeviceEntity?) {
    val isOnline = d?.isOnline == 1
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Filled.Router, null,
                 tint = MaterialTheme.colorScheme.primary,
                 modifier = Modifier.size(36.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(d?.hostname ?: "(unknown)",
                     style = MaterialTheme.typography.titleMedium,
                     fontWeight = FontWeight.SemiBold)
                Text("IP ${d?.lastIp ?: "—"}    MAC ${d?.mac ?: "—"}",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("first seen ${Format.ago(d?.firstSeen)}    last seen ${Format.ago(d?.lastSeen)}",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(if (isOnline) Color(0xFF2CA02C) else Color(0xFF888888)))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium,
         fontWeight = FontWeight.SemiBold,
         modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun BarCard(
    entries: List<BarEntry>,
    labels: List<String>,
    height: Int,
    color: Int
) {
    // Signature of the data we'd need to rebuild for. MPAndroidChart's BarEntry
    // has no stable hashCode, so reduce to a lightweight key the runtime can
    // compare cheaply. If the new poll produced identical numbers, the chart
    // does not re-allocate BarData/BarDataSet on recomposition.
    val dataKey = remember(entries, labels, color) {
        entries.joinToString(",") { "${it.x}:${it.y}" } + "|" + labels.joinToString(",") + "|$color"
    }
    Card(modifier = Modifier.fillMaxWidth(),
         shape = RoundedCornerShape(12.dp),
         colors = CardDefaults.cardColors(
             containerColor = MaterialTheme.colorScheme.surfaceContainerLow
         )) {
        AndroidView(
            factory = { ctx ->
                BarChart(ctx).apply {
                    description.isEnabled = false
                    legend.isEnabled = false
                    setPinchZoom(false)
                    setScaleEnabled(false)
                    setDrawGridBackground(false)
                    axisRight.isEnabled = false
                    axisLeft.textColor = AndroidColor.GRAY
                    axisLeft.setDrawGridLines(true)
                    axisLeft.gridColor = AndroidColor.LTGRAY
                    xAxis.position = XAxis.XAxisPosition.BOTTOM
                    xAxis.granularity = 1f
                    xAxis.setDrawGridLines(false)
                    xAxis.textColor = AndroidColor.GRAY
                    setNoDataText("")
                }
            },
            update = { chart ->
                // Skip the rebuild if dataKey matches the tag we stored last time.
                if (chart.tag == dataKey) return@AndroidView
                val ds = BarDataSet(entries, "MB").apply {
                    this.color = color
                    setDrawValues(false)
                }
                chart.data = BarData(ds).apply { barWidth = 0.8f }
                chart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
                chart.tag = dataKey
                chart.invalidate()
            },
            modifier = Modifier.fillMaxWidth().height(height.dp).padding(8.dp)
        )
    }
}

private fun buildHourlyEntries(rows: List<DeviceUsageDao.HourlyTotal>): List<BarEntry> {
    val buckets = DoubleArray(24)
    for (r in rows) {
        val h = r.hour.toIntOrNull() ?: continue
        if (h in 0..23) buckets[h] = r.total
    }
    return (0..23).map { BarEntry(it.toFloat(), buckets[it].toFloat()) }
}

private fun buildDailyEntries(rows: List<DeviceUsageDao.DailyTotal>): List<BarEntry> {
    val today = LocalDate.now()
    val byDay = rows.associate { it.day to it.total }
    return (0..29).map { i ->
        val date = today.minusDays((29 - i).toLong()).toString()
        BarEntry(i.toFloat(), (byDay[date] ?: 0.0).toFloat())
    }
}

private fun buildDailyLabels(): List<String> {
    val today = LocalDate.now()
    return (0..29).map { i ->
        val date = today.minusDays((29 - i).toLong())
        if (i % 5 == 0) date.toString().substring(5) else ""
    }
}

private fun buildMonthlyEntries(rows: List<DeviceUsageDao.MonthlyTotal>): List<BarEntry> {
    val today = LocalDate.now()
    val byMonth = rows.associate { it.month to it.total }
    return (0..11).map { i ->
        val month = today.minusMonths((11 - i).toLong())
        val key = "%04d-%02d".format(month.year, month.monthValue)
        BarEntry(i.toFloat(), (byMonth[key] ?: 0.0).toFloat())
    }
}

private fun buildMonthlyLabels(): List<String> {
    val today = LocalDate.now()
    return (0..11).map { i ->
        val month = today.minusMonths((11 - i).toLong())
        month.month.name.substring(0, 3).lowercase().replaceFirstChar { it.titlecase() }
    }
}
