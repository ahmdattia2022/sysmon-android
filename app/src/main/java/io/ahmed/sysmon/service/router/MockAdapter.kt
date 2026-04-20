package io.ahmed.sysmon.service.router

import kotlin.random.Random

/**
 * Canned data adapter for Compose previews, demos, and release-mode smoke tests.
 * Never touches the network. WAN bytes advance at ~1–5 MB/s so charts move
 * visibly; device list is stable across calls so the UI can animate in/out.
 */
class MockAdapter(
    private val onSessionEstablished: ((String) -> Unit)? = null,
    private val trace: (String) -> Unit = {}
) : RouterAdapter {

    override val capabilities: Set<RouterCapability> = setOf(
        RouterCapability.COLLECT,
        RouterCapability.REBOOT,
        RouterCapability.WIFI_TOGGLE,
        RouterCapability.BLOCK_MAC,
        RouterCapability.BANDWIDTH_LIMIT
    )

    @Volatile private var wanTx = 40_000_000_000L
    @Volatile private var wanRx = 120_000_000_000L

    override fun collect(): Snapshot {
        trace("mock: collecting")
        onSessionEstablished?.invoke("Cookie=mock-session-" + System.currentTimeMillis())
        val inc = Random.nextLong(1_000_000, 8_000_000)
        wanTx += inc / 4
        wanRx += inc
        val ts = HuaweiHG8145V5Adapter.isoSeconds()
        return Snapshot(
            ts = ts,
            wanTxBytesTotal = wanTx,
            wanRxBytesTotal = wanRx,
            wanTotalBytes = wanTx + wanRx,
            devices = MOCK_DEVICES
        )
    }

    override fun endSession() { trace("mock: session ended") }
    override fun reboot() { trace("mock: reboot triggered (no-op)") }
    override fun setWifiEnabled(on: Boolean) { trace("mock: wifi ${if (on) "on" else "off"}") }
    override fun blockMac(mac: String, blocked: Boolean) {
        trace("mock: ${if (blocked) "block" else "unblock"} $mac")
    }
    override fun setBandwidthLimitKbps(mac: String, downKbps: Int, upKbps: Int) {
        trace("mock: QoS $mac ↓${downKbps}kbps ↑${upKbps}kbps")
    }

    companion object {
        private val MOCK_DEVICES = listOf(
            Device("aa:bb:cc:11:22:33", 12_000, "433", "144", "-42", "ac", "192.168.100.10", "Samsung-A30s"),
            Device("aa:bb:cc:11:22:34", 3_600, "866", "433", "-55", "ac", "192.168.100.11", "MacBook-Pro"),
            Device("aa:bb:cc:11:22:35", 86_400, "6", "6", "-72", "n", "192.168.100.12", "Xiaomi-Bulb"),
            Device("aa:bb:cc:11:22:36", 600, "72", "72", "-66", "n", "192.168.100.13", "Chromecast")
        )
    }
}
