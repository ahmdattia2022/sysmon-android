package io.ahmed.sysmon.util

/**
 * Tiny pure validators. Each returns the user-visible error message, or null
 * when the value is acceptable. Consumers render the message via
 * `OutlinedTextField.supportingText` and disable their Save button when any
 * validator returns non-null.
 */
object Validate {

    fun baseUrl(raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty()) return "Enter the router's URL"
        val prefixOk = v.startsWith("http://", true) || v.startsWith("https://", true)
        if (!prefixOk) return "Must start with http:// or https://"
        val host = v.removePrefix("https://").removePrefix("http://").trimStart('/')
        if (host.isBlank() || host.startsWith("/")) return "Missing host (e.g. 192.168.1.1)"
        return null
    }

    fun notBlank(raw: String, fieldName: String): String? =
        if (raw.trim().isEmpty()) "$fieldName is required" else null

    fun positiveInt(raw: String, fieldName: String, allowZero: Boolean = false): String? {
        val v = raw.trim()
        if (v.isEmpty()) return "$fieldName is required"
        val n = v.toIntOrNull() ?: return "$fieldName must be a whole number"
        if (!allowZero && n <= 0) return "$fieldName must be greater than 0"
        if (allowZero && n < 0) return "$fieldName can't be negative"
        return null
    }

    fun positiveDouble(raw: String, fieldName: String): String? {
        val v = raw.trim()
        if (v.isEmpty()) return "$fieldName is required"
        val n = v.toDoubleOrNull() ?: return "$fieldName must be a number"
        if (n <= 0) return "$fieldName must be greater than 0"
        return null
    }

    /** Loose MAC validator — accepts either AA:BB:CC:DD:EE:FF or AA-BB-... */
    fun mac(raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty()) return "MAC is required"
        val ok = Regex("^[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){5}$").matches(v)
        return if (ok) null else "MAC should look like AA:BB:CC:DD:EE:FF"
    }
}
