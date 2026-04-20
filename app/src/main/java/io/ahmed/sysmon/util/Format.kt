package io.ahmed.sysmon.util

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Number + timestamp formatting helpers. Locale-agnostic. */
object Format {

    private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    /** Human-readable data size. Picks MB/GB based on magnitude. */
    fun mb(value: Double?): String {
        if (value == null) return "—"
        val mb = value
        return when {
            mb >= 1024 -> "%.2f GB".format(mb / 1024.0)
            mb >= 10 -> "%.0f MB".format(mb)
            mb >= 1 -> "%.1f MB".format(mb)
            mb > 0 -> "%.2f MB".format(mb)
            else -> "0 MB"
        }
    }

    fun gb(valueMb: Double?, decimals: Int = 2): String =
        if (valueMb == null) "—" else "%.${decimals}f GB".format(valueMb / 1024.0)

    /** "now" / "12s ago" / "3 min ago" / "2 h ago" / full ISO if > 24 h. */
    fun ago(iso: String?): String {
        if (iso.isNullOrBlank()) return "—"
        val t = runCatching { LocalDateTime.parse(iso, FMT) }.getOrNull() ?: return iso
        val secs = java.time.Duration.between(t, LocalDateTime.now()).seconds
        val a = abs(secs)
        return when {
            a < 5 -> "just now"
            a < 60 -> "${a}s ago"
            a < 3600 -> "${a / 60} min ago"
            a < 86_400 -> "${a / 3600} h ago"
            else -> t.toLocalDate().toString()
        }
    }

    /** "HH:mm" short time from an ISO stamp. */
    fun hhmm(iso: String?): String {
        if (iso.isNullOrBlank()) return "—"
        val t = runCatching { LocalDateTime.parse(iso, FMT) }.getOrNull() ?: return iso
        return "%02d:%02d".format(t.hour, t.minute)
    }
}
