package io.ahmed.sysmon.service.bundle

import io.ahmed.sysmon.data.entity.BundleCycleEntity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Provider-specific regexes that extract bundle-recharge facts from SMS text.
 * Both English and Arabic templates are handled; Arabic numerals are
 * normalised to ASCII digits up front so one regex can match either script.
 *
 * The parsers are pure functions: feed a raw SMS body (+ optional sender) and
 * get back a BundleCycleEntity ready to insert, or null if nothing matched.
 * The same engine powers Settings-screen paste (S2) and SMS auto-read (later).
 */
object SmsParsers {

    private val WE_SENDERS = setOf("WE", "WE TOWE", "WE Egypt", "7100")
    private val VF_SENDERS = setOf("Vodafone", "فودافون", "VodafoneEG")
    private val ORANGE_SENDERS = setOf("Orange", "OrangeEG")
    private val ETISALAT_SENDERS = setOf("Etisalat", "اتصالات")

    /** Returns the first parser match from any provider, or null. */
    fun parse(body: String, sender: String? = null): BundleCycleEntity? {
        val normalized = normalizeArabicDigits(body)
        return parseWe(normalized, sender)
            ?: parseVodafone(normalized, sender)
            ?: parseOrange(normalized, sender)
            ?: parseEtisalat(normalized, sender)
    }

    /**
     * WE examples this matches:
     *   "Your WE 140 GB bundle has been activated. Valid until 15/05/2026."
     *   "تم تفعيل باقة 140 جيجا صالحة حتى 15/05/2026"
     *   "WE recharged with 30 GB top-up. Expires 20/04/2026."
     */
    fun parseWe(normalized: String, sender: String?): BundleCycleEntity? {
        if (sender != null && WE_SENDERS.none { sender.contains(it, ignoreCase = true) } &&
            !normalized.contains("WE", ignoreCase = true) &&
            !normalized.contains("وي")) return null

        val gb = extractGb(normalized) ?: return null
        val validUntil = extractValidUntil(normalized)
        val isTopUp = Regex("top[- ]?up|إضافة|إضافية", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
        return BundleCycleEntity(
            startDateIso = nowIso(),
            totalGb = gb,
            kind = if (isTopUp) BundleCycleEntity.KIND_MANUAL_TOPUP else BundleCycleEntity.KIND_SMS_AUTO,
            provider = "WE",
            note = normalized.take(120),
            validUntilIso = validUntil?.atStartOfDay()?.format(DT_FMT)
        )
    }

    fun parseVodafone(normalized: String, sender: String?): BundleCycleEntity? {
        if (sender != null && VF_SENDERS.none { sender.contains(it, ignoreCase = true) } &&
            !normalized.contains("Vodafone", ignoreCase = true) &&
            !normalized.contains("فودافون")) return null
        val gb = extractGb(normalized) ?: return null
        return BundleCycleEntity(
            startDateIso = nowIso(), totalGb = gb,
            kind = BundleCycleEntity.KIND_SMS_AUTO, provider = "Vodafone",
            note = normalized.take(120),
            validUntilIso = extractValidUntil(normalized)?.atStartOfDay()?.format(DT_FMT)
        )
    }

    fun parseOrange(normalized: String, sender: String?): BundleCycleEntity? {
        if (sender != null && ORANGE_SENDERS.none { sender.contains(it, ignoreCase = true) } &&
            !normalized.contains("Orange", ignoreCase = true)) return null
        val gb = extractGb(normalized) ?: return null
        return BundleCycleEntity(
            startDateIso = nowIso(), totalGb = gb,
            kind = BundleCycleEntity.KIND_SMS_AUTO, provider = "Orange",
            note = normalized.take(120),
            validUntilIso = extractValidUntil(normalized)?.atStartOfDay()?.format(DT_FMT)
        )
    }

    fun parseEtisalat(normalized: String, sender: String?): BundleCycleEntity? {
        if (sender != null && ETISALAT_SENDERS.none { sender.contains(it, ignoreCase = true) } &&
            !normalized.contains("Etisalat", ignoreCase = true) &&
            !normalized.contains("اتصالات")) return null
        val gb = extractGb(normalized) ?: return null
        return BundleCycleEntity(
            startDateIso = nowIso(), totalGb = gb,
            kind = BundleCycleEntity.KIND_SMS_AUTO, provider = "Etisalat",
            note = normalized.take(120),
            validUntilIso = extractValidUntil(normalized)?.atStartOfDay()?.format(DT_FMT)
        )
    }

    // ---- Primitives --------------------------------------------------------

    /** Pulls "140 GB" / "140GB" / "140جيجا" into 140.0. */
    private fun extractGb(text: String): Double? {
        val m = Regex("""(\d+(?:\.\d+)?)\s*(?:GB|G|ج\.?ب?|جيجا)\b""", RegexOption.IGNORE_CASE).find(text)
        return m?.groupValues?.get(1)?.toDoubleOrNull()
    }

    /** Pulls "15/05/2026" / "15-05-2026" / "2026-05-15". */
    private fun extractValidUntil(text: String): LocalDate? {
        val ddmmyyyy = Regex("""(\d{1,2})[/\-](\d{1,2})[/\-](\d{4})""").find(text)
        if (ddmmyyyy != null) {
            val (d, m, y) = ddmmyyyy.destructured
            return runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull()
        }
        val iso = Regex("""(\d{4})-(\d{2})-(\d{2})""").find(text)
        if (iso != null) {
            val (y, m, d) = iso.destructured
            return runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull()
        }
        return null
    }

    /** Arabic-Indic digits → ASCII so the same regex matches both scripts. */
    private fun normalizeArabicDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val c = when (ch.code) {
                in 0x0660..0x0669 -> ('0' + (ch.code - 0x0660))    // Arabic-Indic
                in 0x06F0..0x06F9 -> ('0' + (ch.code - 0x06F0))    // Extended Arabic-Indic (Farsi)
                else -> ch
            }
            sb.append(c)
        }
        return sb.toString()
    }

    private val DT_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    private fun nowIso(): String = LocalDateTime.now().format(DT_FMT)
}
