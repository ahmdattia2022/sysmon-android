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

    override val capabilities: Set<RouterCapability> = setOf(RouterCapability.COLLECT)

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
