package io.ahmed.sysmon.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per billing-cycle anchor: a fresh recharge, a mid-cycle top-up, an
 * SMS-detected renewal, or the user's manual "I have X GB left right now" mark.
 *
 * Remaining-GB math lives in Repository: start from the most recent anchor,
 * sum WAN MB in usage_samples since `startDateIso`, subtract from `totalGb`.
 * `manualRemainingGb` (only set for `MANUAL_REMAINING` rows) overrides the
 * computed balance as a hard reset at `startDateIso`.
 */
@Entity(
    tableName = "bundle_cycles",
    indices = [Index(value = ["startDateIso"])]
)
data class BundleCycleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startDateIso: String,
    val totalGb: Double,
    val kind: String,                        // MANUAL_FRESH | MANUAL_TOPUP | SMS_AUTO | MANUAL_REMAINING
    val provider: String? = null,            // "WE" | "Vodafone" | "Orange" | "Etisalat" | null
    val note: String? = null,
    val manualRemainingGb: Double? = null,
    val validUntilIso: String? = null        // set by SMS parsers when the message included a validity date
) {
    companion object {
        const val KIND_MANUAL_FRESH = "MANUAL_FRESH"
        const val KIND_MANUAL_TOPUP = "MANUAL_TOPUP"
        const val KIND_SMS_AUTO = "SMS_AUTO"
        const val KIND_MANUAL_REMAINING = "MANUAL_REMAINING"
    }
}
