package io.ahmed.sysmon.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import io.ahmed.sysmon.MainActivity
import io.ahmed.sysmon.R
import io.ahmed.sysmon.data.entity.DeviceEntity
import io.ahmed.sysmon.data.entity.JobEntity
import io.ahmed.sysmon.data.entity.UsageSampleEntity
import io.ahmed.sysmon.data.entity.AlertEntity
import io.ahmed.sysmon.data.entity.DeviceUsageEntity
import io.ahmed.sysmon.repo.Repository
import io.ahmed.sysmon.service.router.RouterAdapters
import io.ahmed.sysmon.service.router.RouterKind
import io.ahmed.sysmon.util.Logger
import io.ahmed.sysmon.util.WifiNetworkBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Foreground service: polls the router every 60s. WorkManager's 15-min minimum is too coarse.
 * Acquires a partial wake lock while running so Doze doesn't stall the loop.
 */
class RouterPollService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Per-MAC link-rate memory for the activity-delta attribution heuristic.
     * Keyed by MAC; value = (rxKbps, txKbps) observed at the previous poll.
     * See pollOnce() below for why this exists — tl;dr Huawei exposes only
     * PHY link rate per associated device, which reflects signal strength, not
     * throughput. Weighting WAN delta by absolute link rate over-attributes
     * traffic to devices near the router. Weighting by *change* in link rate
     * is a better (still imperfect) activity proxy.
     */
    private val lastRatesByMac: MutableMap<String, Pair<Double, Double>> = mutableMapOf()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        WifiNetworkBinder.ensureStarted(this)
        startForegroundSafe()
        acquireWakeLock()
        Logger.i(this, "service", "RouterPollService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive != true) {
            job = scope.launch {
                val repo = Repository(applicationContext)
                val evaluator = AnomalyEvaluator(applicationContext, repo)
                var lastFailLogged: String? = null
                var lastPruneDay = ""
                while (isActive) {
                    // Daily log rotation — prune once per local day
                    val today = AnomalyEvaluator.isoSeconds(LocalDateTime.now()).substring(0, 10)
                    if (today != lastPruneDay) {
                        runCatching {
                            Logger.prune(applicationContext, keepDays = 7)
                            Logger.i(applicationContext, "prune",
                                "log_entries older than 7 days dropped")
                        }
                        // Also cap per-device usage history at 90 days so the
                        // device_usage table doesn't grow unbounded (N devices
                        // × 1440 polls/day).
                        runCatching {
                            val cutoff = LocalDate.now().minusDays(90).atStartOfDay()
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
                            val dropped = repo.deviceUsage.deleteBefore(cutoff)
                            Logger.i(applicationContext, "prune",
                                "device_usage: dropped $dropped rows older than 90 days")
                        }
                        lastPruneDay = today
                    }

                    val t0 = System.currentTimeMillis()
                    val status = runCatching {
                        pollOnce(repo)
                        evaluator.evaluateAll()
                    }
                    val now = AnomalyEvaluator.isoSeconds(LocalDateTime.now())
                    val jobStatus = if (status.isSuccess) {
                        lastFailLogged = null
                        "ok"
                    } else {
                        val err = status.exceptionOrNull()
                        val sig = "${err?.javaClass?.simpleName}: ${err?.message ?: ""}".take(120)
                        // Suppress ROUTER_POLL_FAIL alerts while we're off home Wi-Fi —
                        // the router is perfectly fine, we just can't reach it. Log still
                        // captures the attempt so Logs tab stays complete.
                        val onWifi = isOnWifi(applicationContext)
                        if (sig != lastFailLogged && onWifi) {
                            lastFailLogged = sig
                            repo.insertAlert(AlertEntity(
                                ts = now,
                                reason = "ROUTER_POLL_FAIL",
                                valueMb = 0.0,
                                sentOk = 0,
                                error = sig
                            ))
                        }
                        Log.w(TAG, "poll failed: $sig (onWifi=$onWifi)", err)
                        Logger.e(applicationContext, "poll",
                            if (onWifi) "failed" else "failed (off Wi-Fi — alert suppressed)", err)
                        "error: $sig"
                    }
                    repo.upsertJob(JobEntity(
                        name = "router_poll",
                        lastRun = now,
                        lastStatus = jobStatus,
                        nextRun = AnomalyEvaluator.isoSeconds(LocalDateTime.now().plusSeconds(60))
                    ))
                    val elapsed = System.currentTimeMillis() - t0
                    val remaining = (60_000L - elapsed).coerceAtLeast(1_000L)
                    // Sleep until next scheduled poll OR user requests refresh.
                    kotlinx.coroutines.withTimeoutOrNull(remaining) {
                        manualKick.receive()
                        Logger.i(applicationContext, "poll", "manual refresh requested — waking")
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private suspend fun pollOnce(repo: Repository) {
        if (!repo.prefs.loggedIn) return
        val prefs = repo.prefs
        val ctx = applicationContext

        val wifi = WifiNetworkBinder.current()
        Logger.i(ctx, "poll", "start (wifi=${wifi != null})")

        val client = RouterAdapters.forKind(
            kind = RouterKind.fromPref(prefs.routerKind),
            baseUrl = prefs.routerBase,
            username = prefs.routerUser,
            password = prefs.routerPassword,
            cachedCookie = prefs.sessionCookie.takeIf { it.isNotBlank() },
            onSessionEstablished = { sid ->
                prefs.sessionCookie = sid
                prefs.sessionEstablishedAt = System.currentTimeMillis()
                Logger.i(ctx, "login", "session cookie stored (${sid.take(16)}...)")
            },
            trace = { line -> Logger.i(ctx, "client", line) }
        )
        val snap = withContext(Dispatchers.IO) {
            // Process-binding works on every ROM; the network.socketFactory path
            // throws on some OEM builds with "binding socket to network fail".
            WifiNetworkBinder.withWifiProcess(ctx) { client.collect() }
                ?: run {
                    Logger.w(ctx, "poll", "no Wi-Fi network available — trying via default route")
                    client.collect()
                }
        }

        val prev = repo.latestSample("router_wan")
        val deltaMb: Double? = if (prev == null || prev.rxBytes == null || prev.txBytes == null) {
            null
        } else {
            val prevTotal = (prev.rxBytes + prev.txBytes)
            val delta = snap.wanTotalBytes - prevTotal
            if (delta < 0) null else (delta / (1024.0 * 1024.0))
        }

        // ---- Gap detection + backfill ----------------------------------------
        // If the phone was off-Wi-Fi for a while, `prev.ts` will be much older
        // than `snap.ts`. Storing the entire catch-up delta as a single sample at
        // `snap.ts` would smash one hour's chart bucket. Instead we spread the MB
        // uniformly across per-minute synthetic rows flagged backfilled=1.
        val gapSec: Long = if (prev?.ts != null) {
            runCatching {
                val prevDt = java.time.LocalDateTime.parse(prev.ts)
                val nowDt = java.time.LocalDateTime.parse(snap.ts)
                java.time.Duration.between(prevDt, nowDt).seconds.coerceAtLeast(0)
            }.getOrDefault(0)
        } else 0L

        val isCatchup = gapSec > GAP_THRESHOLD_SEC && deltaMb != null && deltaMb > 0
        val topProcessesJson = JSONArray().apply {
            for (d in snap.devices) put(JSONObject()
                .put("mac", d.mac)
                .put("hostname", d.hostname)
                .put("ip", d.ip))
        }.toString()

        if (isCatchup && deltaMb != null) {
            val minutes = (gapSec / 60L).toInt().coerceAtLeast(1)
            val perMinuteMb = round2(deltaMb / minutes)
            val prevDt = java.time.LocalDateTime.parse(prev!!.ts)
            val nowDt = java.time.LocalDateTime.parse(snap.ts)
            val tsFmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

            val rows = (1..minutes).map { i ->
                // Evenly-spaced timestamps between prev (exclusive) and now (inclusive).
                val ts = prevDt.plusSeconds((gapSec * i / minutes))
                    .format(tsFmt)
                UsageSampleEntity(
                    ts = ts,
                    source = "router_wan",
                    rxBytes = if (i == minutes) snap.wanRxBytesTotal else null,
                    txBytes = if (i == minutes) snap.wanTxBytesTotal else null,
                    deltaMb = perMinuteMb,
                    bootTime = null,
                    topProcesses = if (i == minutes) topProcessesJson else null,
                    backfilled = 1
                )
            }
            repo.usage.insertAll(rows)
            Logger.i(ctx, "backfill",
                "filled $minutes min × $perMinuteMb MB over ${gapSec}s offline gap " +
                    "(${prev.ts} → ${snap.ts})")

            // Device attribution is impossible during the gap — WAN delta is real but
            // no per-device signal exists. Record one sentinel row so totals balance
            // and the Devices screen can show an "Offline window" aggregate.
            if (snap.devices.isNotEmpty()) {
                repo.insertDeviceUsage(listOf(
                    io.ahmed.sysmon.data.entity.DeviceUsageEntity(
                        mac = GAP_MAC_SENTINEL,
                        ts = snap.ts,
                        deltaMb = round2(deltaMb),
                        rxRateKbps = 0.0,
                        txRateKbps = 0.0
                    )
                ))
            }
        } else {
            // Normal single-sample insert.
            repo.insertSample(UsageSampleEntity(
                ts = snap.ts,
                source = "router_wan",
                rxBytes = snap.wanRxBytesTotal,
                txBytes = snap.wanTxBytesTotal,
                deltaMb = deltaMb?.let { round2(it) },
                bootTime = null,
                topProcesses = topProcessesJson,
                backfilled = 0
            ))
        }

        // Upsert devices + mark anyone not seen as offline
        val seenMacs = snap.devices.map { it.mac }
        for (d in snap.devices) {
            val existing = repo.devices.byMac(d.mac)
            repo.upsertDevice(DeviceEntity(
                mac = d.mac,
                hostname = d.hostname.ifBlank { existing?.hostname },
                lastIp = d.ip.ifBlank { existing?.lastIp },
                firstSeen = existing?.firstSeen ?: snap.ts,
                lastSeen = snap.ts,
                isOnline = 1
            ))
        }
        if (seenMacs.isNotEmpty()) repo.markDevicesOfflineExcept(seenMacs)

        // Per-device attribution of the WAN delta — activity-delta heuristic.
        // Skip during a catch-up poll — the gap sentinel row already covers it,
        // and mixing real-time activity weights across a multi-minute window
        // would produce misleading per-device history.
        if (isCatchup) {
            return
        }
        //
        // Huawei HG8145V5 exposes only PHY link rate (rxRate/txRate) per
        // associated device. Those are the *negotiated* 802.11 MCS rates —
        // a strong proxy for signal strength, not for bytes transferred.
        // Weighting by absolute link rate systematically over-attributes to
        // devices close to the router (the phone running this app being a
        // prime example).
        //
        // Instead, weight by the *change* in link rate since the previous
        // poll. A device actually moving data has fluctuating link rates;
        // an idle device holds its steady-state negotiated rate. Imperfect,
        // but unbiased w.r.t. proximity. See also: wired devices never appear
        // in this endpoint at all, so their traffic still leaks into the
        // split — that's disclosed in the DevicesScreen caption.
        if (deltaMb != null && deltaMb > 0 && snap.devices.isNotEmpty()) {
            val rxNow = snap.devices.map { parseKbps(it.rxRate) }
            val txNow = snap.devices.map { parseKbps(it.txRate) }
            val activity = snap.devices.mapIndexed { i, d ->
                val prev = lastRatesByMac[d.mac]
                val a = if (prev == null) 0.0
                        else (rxNow[i] - prev.first).coerceAtLeast(0.0) +
                             (txNow[i] - prev.second).coerceAtLeast(0.0)
                lastRatesByMac[d.mac] = rxNow[i] to txNow[i]
                a
            }
            // Evict stale MACs so lastRatesByMac doesn't grow unbounded over
            // months of flapping guests.
            val seen = snap.devices.map { it.mac }.toSet()
            lastRatesByMac.keys.retainAll { it in seen }

            val totalActivity = activity.sum()
            if (totalActivity > 0) {
                val perDevice = snap.devices.mapIndexedNotNull { i, d ->
                    val share = activity[i] / totalActivity
                    if (share <= 0.0) null else DeviceUsageEntity(
                        mac = d.mac, ts = snap.ts,
                        deltaMb = round2(deltaMb * share),
                        rxRateKbps = rxNow[i],
                        txRateKbps = txNow[i]
                    )
                }
                repo.insertDeviceUsage(perDevice)
                Logger.i(applicationContext, "poll",
                    "per-device: attributed ${round2(deltaMb)} MB across " +
                        "${perDevice.size}/${snap.devices.size} active devices")
            } else {
                // No device showed any rate change this minute. The WAN delta
                // is real but we can't honestly assign it — possibly it came
                // from a wired device or from activity that completed fully
                // between two polls. Skip insertion; under-reporting is
                // preferable to a falsely-even split that biases the history.
                Logger.i(applicationContext, "poll",
                    "per-device: ${round2(deltaMb)} MB unattributed " +
                        "(all ${snap.devices.size} devices idle by rate delta)")
            }
        }
    }

    /** Parse a Huawei-reported rate string. Heuristic: number + optional unit. Returns kbps. */
    private fun parseKbps(s: String): Double {
        if (s.isBlank()) return 0.0
        val numMatch = Regex("""[-+]?\d+(?:\.\d+)?""").find(s) ?: return 0.0
        val v = numMatch.value.toDoubleOrNull() ?: return 0.0
        val lower = s.lowercase()
        return when {
            "mbps" in lower || "m/s" in lower -> v * 1000.0
            "kbps" in lower -> v
            "bps" in lower -> v / 1000.0
            else -> v   // router usually reports kbps with no unit
        }
    }

    private fun startForegroundSafe() {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, Notifier.CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sysmon:router-poll").apply {
            setReferenceCounted(false)
            // Upper bound in case onDestroy never fires (process kill, OEM cleanup).
            // The service re-acquires on every start, so 24h is a safe ceiling.
            acquire(24L * 60L * 60L * 1000L)
        }
    }

    private fun round2(v: Double) = Math.round(v * 100.0) / 100.0

    private fun isOnWifi(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
                ?: return false
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        } catch (_: Exception) { false }
    }

    companion object {
        private const val TAG = "RouterPollService"
        private const val NOTIF_ID = 7312

        /** Any poll-to-poll gap beyond this is treated as an offline window that
         *  needs per-minute backfill rather than a single bloated sample. */
        internal const val GAP_THRESHOLD_SEC = 180L

        /** Sentinel MAC for the DeviceUsageEntity row that absorbs gap-window MB. */
        internal const val GAP_MAC_SENTINEL = "_gap_"

        /** Shared across instances; a single send wakes the poll loop immediately. */
        @JvmStatic
        internal val manualKick = kotlinx.coroutines.channels.Channel<Unit>(capacity = 1)

        fun start(context: Context) {
            val intent = Intent(context, RouterPollService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RouterPollService::class.java))
        }

        /** User-triggered "refresh now" — no-op if the service isn't running. */
        fun requestRefresh() {
            manualKick.trySend(Unit)
        }
    }
}
