package io.ahmed.sysmon.service.router

/**
 * Abstract router scraping + control surface. Concrete adapters implement
 * the required `collect()` / `endSession()` pair for polling; optional
 * control methods (reboot, toggle Wi-Fi, block a MAC, bandwidth-limit) throw
 * UnsupportedOperationException by default. The `capabilities` set tells the
 * UI which controls to enable.
 */
interface RouterAdapter {
    /** Log in if necessary, then fetch WAN totals + device list in one shot. */
    fun collect(): Snapshot

    /** Tell the router we're done — frees admin slots on routers with single-admin limits. */
    fun endSession()

    /** Reboot the router. Default: not supported. */
    fun reboot() { throw UnsupportedOperationException("reboot not supported by this router model") }

    /** Turn the Wi-Fi radio on or off. Default: not supported. */
    fun setWifiEnabled(on: Boolean) { throw UnsupportedOperationException("Wi-Fi toggle not supported") }

    /** Block or unblock a specific MAC via the router's ACL. Default: not supported. */
    fun blockMac(mac: String, blocked: Boolean) { throw UnsupportedOperationException("MAC block not supported") }

    /** Set per-device bandwidth cap in kbps. Default: not supported. */
    fun setBandwidthLimitKbps(mac: String, downKbps: Int, upKbps: Int) {
        throw UnsupportedOperationException("QoS not supported by this router model")
    }

    /** Capabilities the UI should enable for this adapter. */
    val capabilities: Set<RouterCapability>
}

enum class RouterCapability {
    COLLECT,          // always present
    REBOOT,
    WIFI_TOGGLE,
    BLOCK_MAC,
    BANDWIDTH_LIMIT
}

/**
 * Router model the user selects in Settings. Each value has a display name
 * and a suggested admin URL. `adapterAvailable` tells the dropdown whether
 * the adapter exists yet — helpful while we iterate on new models.
 */
enum class RouterKind(
    val displayName: String,
    val suggestedBaseUrl: String,
    val adapterAvailable: Boolean
) {
    HUAWEI_HG8145V5("Huawei HG8145V5 (WE Fibre)", "https://192.168.100.1", true),
    ZTE_F660("ZTE F660 (Vodafone)", "http://192.168.1.1", false),
    TPLINK_ARCHER("TP-Link Archer", "http://192.168.1.1", false),
    GENERIC_HTTP("Generic HTTP (custom)", "http://192.168.1.1", false),
    MOCK("Mock (demo data)", "mock://local", true);

    companion object {
        fun fromPref(raw: String?): RouterKind =
            values().firstOrNull { it.name == raw } ?: HUAWEI_HG8145V5
    }
}

data class WanStats(
    val txPackets: Long, val txBytes: Long, val txErrors: Long, val txDropped: Long,
    val rxPackets: Long, val rxBytes: Long, val rxErrors: Long, val rxDropped: Long
)

data class Device(
    val mac: String,
    val uptimeSec: Int,
    val rxRate: String,
    val txRate: String,
    val rssi: String,
    val mode: String,
    val ip: String,
    val hostname: String
)

data class Snapshot(
    val ts: String,
    val wanTxBytesTotal: Long,
    val wanRxBytesTotal: Long,
    val wanTotalBytes: Long,
    val devices: List<Device>
)
