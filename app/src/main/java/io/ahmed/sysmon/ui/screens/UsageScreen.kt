package io.ahmed.sysmon.ui.screens

import android.graphics.Color as AndroidColor
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ahmed.sysmon.service.RouterPollService
import io.ahmed.sysmon.util.rememberToday
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import io.ahmed.sysmon.repo.Repository
import io.ahmed.sysmon.util.Format
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen() {
    val context = LocalContext.current
    val repo = remember { Repository(context) }
    val scope = rememberCoroutineScope()

    // refreshTick re-reads non-reactive EncryptedSharedPreferences. Bumping it on
    // pull-to-refresh picks up threshold changes made in the Settings screen.
    var refreshTick by remember { mutableIntStateOf(0) }
    val thresholdMb = remember(refreshTick) { repo.prefs.routerHourlyMbLimit }

    // rememberToday() flips at midnight — all keyed `remember(today) { ... }` below
    // recompute, causing flows to re-subscribe with the fresh startIso.
    val today = rememberToday()
    val todayKey = remember(today) { today.toString() }
    val startIso = remember(today) {
        today.atStartOfDay().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
    }
    val todaySamples by repo.usage
        .sumMbSinceFlow("router_wan", startIso)
        .collectAsStateWithLifecycle(0.0)

    // Hourly + 7-day arrays are aggregated on a background thread in response to
    // flow ticks. We gate on todaySamples so a fresh poll triggers recompute.
    var hourly by remember { mutableStateOf(DoubleArray(24)) }
    var sevenDays by remember { mutableStateOf(DoubleArray(7)) }
    var todayPeakMb by remember { mutableDoubleStateOf(0.0) }
    var todayTotalMb by remember { mutableDoubleStateOf(0.0) }

    LaunchedEffect(todaySamples, today) {
        val samples = repo.usage.since("router_wan", startIso)
        val buckets = DoubleArray(24)
        for (s in samples) {
            val d = s.deltaMb ?: continue
            val h = runCatching { LocalDateTime.parse(s.ts).hour }.getOrNull() ?: continue
            buckets[h] += d
        }
        hourly = buckets
        todayPeakMb = buckets.maxOrNull() ?: 0.0
        todayTotalMb = buckets.sum()

        val week = DoubleArray(7)
        for (i in 0..6) {
            val d = today.minusDays((6 - i).toLong()).toString()
            week[i] = repo.usage.sumMbOnDay("router_wan", d)
        }
        sevenDays = week
    }

    var isRefreshing by remember { mutableStateOf(false) }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            scope.launch {
                isRefreshing = true
                RouterPollService.requestRefresh()
                refreshTick++
                delay(1200)
                isRefreshing = false
            }
        },
        modifier = Modifier.fillMaxSize()
    ) {
        Column(modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)) {

            Text("Usage", style = MaterialTheme.typography.headlineMedium,
                 fontWeight = FontWeight.Bold)
            Text(todayKey,
                 style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))

            // Stats row — animate the hero numbers on every flow emission
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(Modifier.weight(1f), "Today total", Format.mb(todayTotalMb))
                StatTile(Modifier.weight(1f), "Peak hour", Format.mb(todayPeakMb))
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("Hourly (today)  ·  threshold $thresholdMb MB")

            BarChartCard(hourly, thresholdMb)

            Spacer(Modifier.height(20.dp))
            SectionLabel("7-day trend")

            WeekTrendCard(sevenDays, today)

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BarChartCard(hourly: DoubleArray, thresholdMb: Int) {
    // Stable key — skip BarData rebuild when numbers didn't change
    val key = remember(hourly, thresholdMb) {
        buildString {
            for (v in hourly) { append(v); append(',') }
            append('|'); append(thresholdMb)
        }
    }
    Card(modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
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
                    axisLeft.setDrawGridLines(true)
                    axisLeft.textColor = AndroidColor.GRAY
                    axisLeft.gridColor = AndroidColor.LTGRAY
                    xAxis.position = XAxis.XAxisPosition.BOTTOM
                    xAxis.granularity = 1f
                    xAxis.setDrawGridLines(false)
                    xAxis.textColor = AndroidColor.GRAY
                    xAxis.valueFormatter = IndexAxisValueFormatter(
                        (0..23).map { "%02d".format(it) }
                    )
                    setNoDataText("")
                }
            },
            update = { chart ->
                if (chart.tag == key) return@AndroidView
                val entries = hourly.mapIndexed { i, v -> BarEntry(i.toFloat(), v.toFloat()) }
                val ds = BarDataSet(entries, "MB").apply {
                    colors = hourly.map {
                        if (it > thresholdMb) AndroidColor.rgb(214, 39, 40)
                        else AndroidColor.rgb(31, 119, 180)
                    }
                    setDrawValues(false)
                }
                chart.data = BarData(ds).apply { barWidth = 0.8f }
                chart.axisLeft.removeAllLimitLines()
                chart.axisLeft.addLimitLine(LimitLine(thresholdMb.toFloat()).apply {
                    lineColor = AndroidColor.rgb(180, 180, 180)
                    lineWidth = 1f
                    enableDashedLine(8f, 6f, 0f)
                })
                chart.tag = key
                chart.invalidate()
            },
            modifier = Modifier.fillMaxWidth().height(220.dp).padding(8.dp)
        )
    }
}

@Composable
private fun WeekTrendCard(sevenDays: DoubleArray, today: LocalDate) {
    val key = remember(sevenDays, today) {
        buildString {
            for (v in sevenDays) { append(v); append(',') }
            append('|'); append(today.toString())
        }
    }
    Card(modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
         shape = RoundedCornerShape(12.dp),
         colors = CardDefaults.cardColors(
             containerColor = MaterialTheme.colorScheme.surfaceContainerLow
         )) {
        AndroidView(
            factory = { ctx ->
                LineChart(ctx).apply {
                    description.isEnabled = false
                    legend.isEnabled = false
                    axisRight.isEnabled = false
                    xAxis.position = XAxis.XAxisPosition.BOTTOM
                    xAxis.granularity = 1f
                    xAxis.setDrawGridLines(false)
                    xAxis.textColor = AndroidColor.GRAY
                    axisLeft.textColor = AndroidColor.GRAY
                    setScaleEnabled(false)
                    setPinchZoom(false)
                    setNoDataText("")
                }
            },
            update = { chart ->
                if (chart.tag == key) return@AndroidView
                val entries = sevenDays.mapIndexed { i, v ->
                    Entry(i.toFloat(), (v / 1024f).toFloat())
                }
                val ds = LineDataSet(entries, "GB/day").apply {
                    color = AndroidColor.rgb(31, 119, 180)
                    setCircleColor(AndroidColor.rgb(31, 119, 180))
                    lineWidth = 2.5f
                    circleRadius = 4f
                    setDrawValues(false)
                    setDrawFilled(true)
                    fillColor = AndroidColor.rgb(31, 119, 180)
                    fillAlpha = 40
                }
                chart.xAxis.valueFormatter = IndexAxisValueFormatter(
                    (0..6).map { today.minusDays((6 - it).toLong()).toString().substring(5) }
                )
                chart.data = LineData(ds)
                chart.tag = key
                chart.invalidate()
            },
            modifier = Modifier.fillMaxWidth().height(220.dp).padding(8.dp)
        )
    }
}

@Composable
private fun StatTile(modifier: Modifier, label: String, value: String) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            AnimatedContent(
                targetState = value,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "stat-$label"
            ) { v ->
                Text(v, style = MaterialTheme.typography.titleLarge,
                     fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium,
         fontWeight = FontWeight.SemiBold)
}
