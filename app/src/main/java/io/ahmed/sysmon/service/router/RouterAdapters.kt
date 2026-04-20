package io.ahmed.sysmon.service.router

/**
 * Factory: pick the right adapter based on user-selected router kind.
 * Unavailable kinds fall back to the Huawei adapter so the service never
 * crashes on a stale preference value.
 */
object RouterAdapters {
    fun forKind(
        kind: RouterKind,
        baseUrl: String,
        username: String,
        password: String,
        cachedCookie: String? = null,
        onSessionEstablished: ((String) -> Unit)? = null,
        trace: (String) -> Unit = {}
    ): RouterAdapter = when (kind) {
        RouterKind.HUAWEI_HG8145V5 -> HuaweiHG8145V5Adapter(
            baseUrl, username, password, cachedCookie, onSessionEstablished, trace
        )
        RouterKind.MOCK -> MockAdapter(onSessionEstablished, trace)
        // No adapter yet for these — fall back and log a warning.
        RouterKind.ZTE_F660,
        RouterKind.TPLINK_ARCHER,
        RouterKind.GENERIC_HTTP -> {
            trace("adapter for $kind not implemented yet — falling back to Huawei")
            HuaweiHG8145V5Adapter(baseUrl, username, password, cachedCookie, onSessionEstablished, trace)
        }
    }
}
