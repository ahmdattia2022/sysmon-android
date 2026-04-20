package io.ahmed.sysmon.service.router

import android.util.Base64
import io.ahmed.sysmon.util.hexDecode
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Huawei HG8145V5 scraper — ported from the original monolithic RouterClient.
 *
 * NOTE: OkHttp's built-in CookieJar is bypassed on purpose. Huawei sends
 * `Set-Cookie: Cookie=<hash>:id=1` with colons in the value; OkHttp's strict
 * RFC 6265 parser silently drops fields it doesn't like, and the router then
 * sees an invalid session on the next request. We use CookieJar.NO_COOKIES and
 * manage a single `sessionCookie` string manually.
 *
 * Capabilities declared: COLLECT only for now. Reboot / Wi-Fi toggle / MAC
 * block / QoS are on the Sprint 4 docket.
 */
class HuaweiHG8145V5Adapter(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
    private val cachedCookie: String? = null,
    private val onSessionEstablished: ((String) -> Unit)? = null,
    private val trace: (String) -> Unit = {}
) : RouterAdapter {

    override val capabilities: Set<RouterCapability> = setOf(
        RouterCapability.COLLECT
        // Other caps (reboot / Wi-Fi toggle / block / QoS) are kept in code
        // but not advertised. On WE-branded firmware, /html/bbsp/wlanfilter/ and
        // /html/bbsp/bandwidthcontrol/ return HTTP 404 to the standard `admin`
        // user; these endpoints are only exposed to `telecomadmin`. Until we
        // either (a) capture the real admin page HTML or (b) let the user
        // enter super-admin credentials, we won't lie to the UI about what we
        // can do.
    )

    @Volatile private var sessionCookie: String? = null

    private val client: OkHttpClient = sharedClient()

    private fun currentCookieHeader(): String? = sessionCookie?.takeIf { it.isNotBlank() }

    private fun call(req: Request, followRedirects: Boolean = true): Response {
        val b = req.newBuilder()
        currentCookieHeader()?.let { b.header("Cookie", it) }
        val cli = if (followRedirects) client else client.newBuilder()
            .followRedirects(false).followSslRedirects(false).build()
        val resp = cli.newCall(b.build()).execute()
        val raws = resp.headers("Set-Cookie") +
            resp.headers("set-cookie") +
            resp.headers("Set-cookie")
        for (raw in raws) {
            val m = Regex("""Cookie=([^;,\s]+)""").find(raw)
            if (m != null) {
                sessionCookie = "Cookie=${m.groupValues[1]}"
                break
            }
        }
        return resp
    }

    override fun collect(): Snapshot {
        currentTrace = trace
        controlMutex.lock()
        try {
            return doCollect()
        } finally {
            controlMutex.unlock()
        }
    }

    private fun doCollect(): Snapshot {
        var needLogin = true
        if (!cachedCookie.isNullOrBlank()) {
            sessionCookie = cachedCookie
            if (probeSession()) {
                needLogin = false
                trace("session reused")
            } else {
                trace("cached session rejected — re-login")
            }
        }
        if (needLogin) {
            trace("logging in")
            login()
            trace("login ok")
        }
        val wan = fetchWanStats()
        trace("WAN stats: ${wan.size} connections")
        val devices = fetchAssociatedDevices()
        trace("devices: ${devices.size}")

        val totalTx = wan.values.sumOf { it.txBytes.takeIf { b -> b > 0 } ?: 0L }
        val totalRx = wan.values.sumOf { it.rxBytes.takeIf { b -> b > 0 } ?: 0L }
        val ts = isoSeconds()
        return Snapshot(
            ts = ts,
            wanTxBytesTotal = totalTx,
            wanRxBytesTotal = totalRx,
            wanTotalBytes = totalTx + totalRx,
            devices = devices
        )
    }

    override fun endSession() {
        currentTrace = trace
        runCatching { logout() }
    }

    // ---- Control actions --------------------------------------------------
    //
    // Huawei's admin pages all follow the same idiom: GET the landing page to
    // pick up a fresh `x.X_HW_Token` nonce, then POST a form with the new
    // values + that token. Exact field names + endpoints vary by firmware, so
    // the impls below hit the most common paths and log a warning on 4xx so
    // the user can inspect via the Logs tab and refine.
    //
    // All three take the control mutex so we don't interleave with a poll
    // (which would steal the session cookie mid-operation).

    override fun setWifiEnabled(on: Boolean) {
        currentTrace = trace
        controlMutex.lock()
        try {
            ensureSession()
            val token = fetchToken("/html/bbsp/wlanbasic/wlanbasic.asp") ?: run {
                trace("setWifiEnabled: couldn't fetch CSRF token"); return
            }
            val form = FormBody.Builder()
                .add("x.X_HW_Token", token)
                .add("x.X_HW_WLANEnable", if (on) "1" else "0")
                .build()
            val req = Request.Builder()
                .url("$baseUrl/html/bbsp/wlanbasic/wlanbasic.asp")
                .post(form)
                .header("Referer", "$baseUrl/html/bbsp/wlanbasic/wlanbasic.asp")
                .build()
            val code = call(req).use { it.code }
            trace("setWifiEnabled($on) -> HTTP $code")
            if (code !in 200..299) throw RuntimeException("Wi-Fi toggle HTTP $code")
        } finally {
            controlMutex.unlock()
        }
    }

    override fun blockMac(mac: String, blocked: Boolean) {
        currentTrace = trace
        controlMutex.lock()
        try {
            ensureSession()
            val token = fetchToken("/html/bbsp/wlanfilter/wlanfilter.asp") ?: run {
                trace("blockMac: couldn't fetch CSRF token"); return
            }
            // Common Huawei form shape: Enable=1 + Mode=1 (blacklist) + MAC list.
            // We append one MAC on block, remove on unblock. Because the page
            // expects the whole list, best-effort here sends just this MAC; on
            // user-visible firmwares the list merges correctly.
            val form = FormBody.Builder()
                .add("x.X_HW_Token", token)
                .add("x.X_HW_MACFilterEnable", if (blocked) "1" else "0")
                .add("x.X_HW_MACFilterMode", "1")
                .add("x.MACAddress", mac.uppercase())
                .add("x.Action", if (blocked) "add" else "remove")
                .build()
            val req = Request.Builder()
                .url("$baseUrl/html/bbsp/wlanfilter/wlanfilter.asp")
                .post(form)
                .header("Referer", "$baseUrl/html/bbsp/wlanfilter/wlanfilter.asp")
                .build()
            val code = call(req).use { it.code }
            trace("blockMac($mac, blocked=$blocked) -> HTTP $code")
            if (code !in 200..299) throw RuntimeException("Block-MAC HTTP $code")
        } finally {
            controlMutex.unlock()
        }
    }

    override fun setBandwidthLimitKbps(mac: String, downKbps: Int, upKbps: Int) {
        currentTrace = trace
        controlMutex.lock()
        try {
            ensureSession()
            val token = fetchToken("/html/bbsp/bandwidthcontrol/bandwidthcontrol.asp")
                ?: fetchToken("/html/bbsp/wlanqos/wlanqos.asp")
                ?: run { trace("QoS: couldn't fetch CSRF token"); return }
            // Most HG8145V5 firmwares expose bandwidth control at
            // /html/bbsp/bandwidthcontrol/. 0 == unlimited.
            val form = FormBody.Builder()
                .add("x.X_HW_Token", token)
                .add("x.X_HW_BandwidthEnable", if (downKbps > 0 || upKbps > 0) "1" else "0")
                .add("x.MACAddress", mac.uppercase())
                .add("x.X_HW_DownstreamRate", downKbps.toString())
                .add("x.X_HW_UpstreamRate", upKbps.toString())
                .build()
            val req = Request.Builder()
                .url("$baseUrl/html/bbsp/bandwidthcontrol/bandwidthcontrol.asp")
                .post(form)
                .header("Referer", "$baseUrl/html/bbsp/bandwidthcontrol/bandwidthcontrol.asp")
                .build()
            val code = call(req).use { it.code }
            trace("setBandwidthLimitKbps($mac, ↓$downKbps, ↑$upKbps) -> HTTP $code")
            if (code !in 200..299) throw RuntimeException("QoS HTTP $code")
        } finally {
            controlMutex.unlock()
        }
    }

    /**
     * Login if no session is cached; used before every control call so a
     * control invoked when the poll loop is idle still works.
     */
    private fun ensureSession() {
        if (sessionCookie.isNullOrBlank() ||
            sessionCookie?.contains(":id=-1") == true ||
            sessionCookie?.contains(":id=0") == true ||
            !probeSession()
        ) {
            login()
        }
    }

    /**
     * GETs an admin page and extracts the CSRF nonce Huawei requires on every
     * form POST. On HG8145V5 the token lives in a hidden input `<input
     * type="hidden" id="onttoken" ... value="HEX">` (attribute order varies).
     * The JS layer refers to it everywhere via `getValue('onttoken')`.
     *
     * Falls back to `/index.asp` — every authenticated page embeds the same
     * token, so even if the specific admin endpoint is 404 on this firmware,
     * we can still sign the subsequent POST.
     */
    private fun fetchToken(adminPath: String): String? {
        tokenFromPath(adminPath)?.let { return it }
        if (adminPath != "/index.asp") {
            tokenFromPath("/index.asp")?.let { return it }
        }
        return null
    }

    private fun tokenFromPath(path: String): String? {
        val resp = call(Request.Builder().url("$baseUrl$path")
            .header("Referer", "$baseUrl/index.asp").get().build())
        val body = resp.use { it.body?.string().orEmpty() }
        // 1. Canonical: <input ... onttoken ... value="HEX" ...> (any attr order)
        val tag = Regex("""<input[^>]*\bonttoken\b[^>]*>""", RegexOption.IGNORE_CASE).find(body)
        if (tag != null) {
            val v = Regex("""value\s*=\s*["']([^"']+)""", RegexOption.IGNORE_CASE)
                .find(tag.value)?.groupValues?.get(1)
            if (!v.isNullOrBlank()) return v
        }
        // 2. Inline JS assignments some pages use: var onttoken = "HEX";
        Regex("""onttoken['"]?\s*[:=]\s*['"]([0-9a-fA-F]+)['"]""").find(body)
            ?.groupValues?.get(1)?.let { return it }
        // 3. Older token name retained just in case firmware changes it.
        Regex("""X_HW_Token["']\s*(?:content=|value=)\s*["']([^"']+)""").find(body)
            ?.groupValues?.get(1)?.let { return it }

        // Give future debugging a fighting chance — log the first chunk of the
        // page so a reader can spot the real token field name.
        val preview = body.take(320).replace(Regex("""\s+"""), " ")
        trace("fetchToken($path): no onttoken found; preview=$preview")
        return null
    }

    private fun probeSession(): Boolean = try {
        val r = call(
            Request.Builder().url("$baseUrl/index.asp")
                .header("Referer", "$baseUrl/").get().build(),
            followRedirects = false
        )
        r.use {
            it.code in 200..299 && it.header("Location")?.contains("login", true) != true
        }
    } catch (_: Exception) { false }

    private fun login() {
        val prime = call(Request.Builder().url("$baseUrl/").get().build())
        prime.use { r ->
            trace("prime / -> ${r.code} proto=${r.protocol} cookie=${sessionCookie?.take(32)}")
            for ((n, v) in r.headers) trace("  < $n: ${v.take(120)}")
        }

        val tokenReq = Request.Builder()
            .url("$baseUrl/asp/GetRandCount.asp")
            .post("".toRequestBody(null))
            .header("Referer", "$baseUrl/")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        val token = call(tokenReq).use { r ->
            val body = r.body?.string().orEmpty()
            val clean = body.removePrefix("\uFEFF").trim()
            trace("token -> ${r.code} len=${clean.length} preview=${clean.take(12)}…")
            clean
        }
        if (token.isEmpty()) {
            throw RuntimeException("Login failed: empty CSRF token (router page structure changed?)")
        }

        sessionCookie = "Cookie=body:Language:english:id=-1"

        val form = FormBody.Builder()
            .add("UserName", username)
            .add("PassWord", Base64.encodeToString(password.toByteArray(), Base64.NO_WRAP))
            .add("Language", "english")
            .add("x.X_HW_Token", token)
            .build()
        val loginReq = Request.Builder()
            .url("$baseUrl/login.cgi")
            .post(form)
            .header("Referer", "$baseUrl/")
            .build()

        val dump = call(loginReq, followRedirects = false).use { r ->
            val headerLines = r.headers.map { "${it.first}: ${it.second}" }
            val sc = (r.headers("Set-Cookie") + r.headers("set-cookie") + r.headers("Set-cookie"))
                .distinct()
            val body = r.peekBody(400).string()
            val code = r.code
            val proto = r.protocol.toString()
            code to Quad(proto, headerLines, sc, body)
        }
        val setCookies = dump.second.setCookies
        trace("login POST -> ${dump.first} proto=${dump.second.proto} set-cookies=${setCookies.size}")
        for (h in dump.second.headers) trace("  < $h".take(140))
        if (setCookies.isEmpty()) {
            trace("  body[0..250]: ${dump.second.body.take(250)}")
        } else {
            trace("  set-cookie[0]: ${setCookies.first()}")
        }

        val current = sessionCookie ?: ""
        if (":id=-1" in current || ":id=0" in current || current.isBlank() ||
            current == "Cookie=body:Language:english:id=-1"
        ) {
            throw RuntimeException(
                "Login failed: no session cookie returned. status=${dump.first}, " +
                    "set-cookie headers=${setCookies.size}. See Logs screen for dump."
            )
        }
        onSessionEstablished?.invoke(current)
    }

    private fun logout() {
        call(Request.Builder().url("$baseUrl/logout.cgi").get().build()).close()
    }

    private fun fetchWanStats(): Map<String, WanStats> {
        call(Request.Builder().url("$baseUrl/index.asp")
            .header("Referer", "$baseUrl/").get().build()).close()

        val resp = call(Request.Builder().url("$baseUrl/html/bbsp/waninfo/waninfo.asp")
            .header("Referer", "$baseUrl/index.asp").get().build())
        val decoded = resp.use { it.body?.string().orEmpty().let(::hexDecode) }

        val re = Regex(
            """(WAN(?:PPPConnection|IPConnection)\.\d+\.Stats)","(\d+)","(\d+)","(\d+)","(\d+)","(\d+)","(\d+)","(\d+)","(\d+)""""
        )
        val out = mutableMapOf<String, WanStats>()
        for (m in re.findAll(decoded)) {
            val g = m.groupValues
            out[g[1]] = WanStats(
                txPackets = g[2].toLong(), txBytes = g[3].toLong(),
                txErrors = g[4].toLong(), txDropped = g[5].toLong(),
                rxPackets = g[6].toLong(), rxBytes = g[7].toLong(),
                rxErrors = g[8].toLong(), rxDropped = g[9].toLong()
            )
        }
        return out
    }

    private fun fetchAssociatedDevices(): List<Device> {
        call(Request.Builder().url("$baseUrl/html/amp/wlaninfo/wlaninfo.asp")
            .header("Referer", "$baseUrl/index.asp").get().build()).close()

        val req = Request.Builder()
            .url("$baseUrl/html/amp/wlaninfo/getassociateddeviceinfo.asp")
            .post("".toRequestBody(null))
            .header("Referer", "$baseUrl/html/amp/wlaninfo/wlaninfo.asp")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()
        val decoded = call(req).use { it.body?.string().orEmpty().let(::hexDecode) }

        val re = Regex(
            """new\s+stAssociatedDevice\(\s*""" +
                """"([^"]*)"\s*,""" +
                """\s*"([^"]*)"\s*,""" +
                """\s*"([^"]*)"\s*,""" +
                """\s*"([^"]*)"\s*,""" +
                """\s*"([^"]*)"\s*,""" +
                """\s*"([^"]*)"\s*,""" +
                """\s*"([^"]*)"\s*,""" +
                """\s*"([^"]*)"\s*,""" +
                """\s*"([^"]*)"\s*,""" +
                """\s*"([^"]*)"\s*,""" +
                """\s*"([^"]*)"\s*,""" +
                """\s*"([^"]*)"\s*,""" +
                """\s*"([^"]*)"\s*,""" +
                """\s*"([^"]*)"\s*,""" +
                """\s*"([^"]*)""""
        )
        return re.findAll(decoded).map { m ->
            val g = m.groupValues
            Device(
                mac = g[2],
                uptimeSec = g[3].toIntOrNull() ?: 0,
                rxRate = g[4], txRate = g[5], rssi = g[6], mode = g[10],
                ip = g[14], hostname = g[15]
            )
        }.toList()
    }

    // ---- Shared client plumbing ------------------------------------------

    private fun sharedClient(): OkHttpClient = shared ?: synchronized(Companion) {
        shared ?: buildSharedClient().also { shared = it }
    }

    private fun buildSharedClient(): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
        return OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(2, 60, TimeUnit.SECONDS))
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .addInterceptor(okhttp3.Interceptor { chain ->
                val req = chain.request()
                val b = req.newBuilder()
                if (req.header("User-Agent") == null) b.header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Safari/537.36"
                )
                if (req.header("Accept") == null) b.header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9," +
                        "image/avif,image/webp,image/apng,*/*;q=0.8"
                )
                if (req.header("Accept-Language") == null)
                    b.header("Accept-Language", "en-US,en;q=0.9")
                val out = b.build()
                val tr = currentTrace
                tr("> ${out.method} ${out.url.encodedPath}")
                for ((n, v) in out.headers) {
                    val display = if (n.equals("Cookie", true)) v.take(70) else v
                    tr("  > $n: $display")
                }
                chain.proceed(out)
            })
            .build()
    }

    private data class Quad(val proto: String, val headers: List<String>,
                             val setCookies: List<String>, val body: String)

    companion object {
        @Volatile private var shared: OkHttpClient? = null
        @Volatile private var currentTrace: (String) -> Unit = {}

        /**
         * Serializes control actions (reboot/wifi/block/QoS) against the poll
         * loop's `collect()`. The adapter is a light wrapper around a shared
         * OkHttpClient with a single session cookie — concurrent calls would
         * corrupt it. `java.util.concurrent.locks.ReentrantLock` is fine here;
         * we don't block for long.
         */
        private val controlMutex = java.util.concurrent.locks.ReentrantLock()

        fun isoSeconds(): String {
            val cal = java.util.Calendar.getInstance()
            return String.format(
                "%04d-%02d-%02dT%02d:%02d:%02d",
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH),
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE),
                cal.get(java.util.Calendar.SECOND)
            )
        }
    }
}
