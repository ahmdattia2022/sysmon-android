package io.ahmed.sysmon.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A device seen on the home network, keyed by MAC.
 *
 * `hostname` is whatever the router reports; it stays authoritative for display
 * unless the user has set a custom `label`. `label` / `group` / `iconKind` are
 * pure user overrides — we never auto-fill them. The `dailyBudgetMb` and
 * `monthlyBudgetMb` drive per-device alerts in AnomalyEvaluator.
 */
@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val mac: String,
    val hostname: String?,
    val lastIp: String?,
    val firstSeen: String,
    val lastSeen: String,
    val isOnline: Int,

    /** User-supplied nickname — null means show the router's hostname instead. */
    val label: String? = null,
    /** User-assigned category, e.g. "Parents", "Kids", "IoT". Null means ungrouped. */
    val group: String? = null,
    /** One of the IconKind names (Smartphone, Laptop, Tv, …) so the list renders quickly. */
    val iconKind: String? = null,
    /** Daily budget in MB; null disables the alert. */
    val dailyBudgetMb: Int? = null,
    /** Monthly budget in MB; null disables the alert. */
    val monthlyBudgetMb: Int? = null
)
