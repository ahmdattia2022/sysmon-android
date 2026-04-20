package io.ahmed.sysmon.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Returns today's local date as reactive state. The value flips automatically
 * the next time the device clock crosses local midnight, so any Flow keyed on
 * this value will re-subscribe with the new day's SQL args.
 *
 * Why: screens previously captured `LocalDate.now()` inside a parameterless
 * `remember { ... }`, which froze the value for the lifetime of the composable.
 * If the user had the app open across midnight, "today" stayed stale until the
 * composable left composition entirely (tab switch with `saveState` preserves
 * it longer than you'd expect).
 *
 * Cost: one `delay` per day. Cancelled with the composable.
 */
@Composable
fun rememberToday(): LocalDate {
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = LocalDateTime.now()
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay()
            val delayMs = Duration.between(now, nextMidnight).toMillis()
                .coerceAtLeast(1_000L) + 500L
            delay(delayMs)
            today = LocalDate.now()
        }
    }
    return today
}
