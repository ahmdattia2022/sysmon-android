package io.ahmed.sysmon.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Minimal recurring block-window editor. Keeps things tight:
 *  - 7 day chips (Sun–Sat). Bitmask packed into a single int.
 *  - start/end as HH:mm text fields (validated on save).
 *
 * Future sprints can replace the time fields with Material TimePickers.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BlockScheduleDialog(
    deviceName: String,
    onDismiss: () -> Unit,
    onConfirm: (daysMask: Int, startMin: Int, endMin: Int) -> Unit
) {
    var daysMask by remember { mutableIntStateOf(0b0111110) } // Mon–Fri by default
    var startText by remember { mutableStateOf("22:00") }
    var endText by remember { mutableStateOf("07:00") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Block schedule · $deviceName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Days", style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val names = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
                    for (i in 0..6) {
                        val bit = 1 shl i
                        val selected = (daysMask and bit) != 0
                        FilterChip(
                            selected = selected,
                            onClick = {
                                daysMask = if (selected) daysMask and bit.inv() else daysMask or bit
                            },
                            label = { Text(names[i]) }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = startText, onValueChange = { startText = it },
                        label = { Text("Start HH:mm") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = endText, onValueChange = { endText = it },
                        label = { Text("End HH:mm") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                         style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    "Device is blocked during the window and unblocked at the end. " +
                        "If the end time is earlier than the start, it rolls into the next day.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val start = parseMinute(startText)
                val end = parseMinute(endText)
                when {
                    daysMask == 0 -> error = "Pick at least one day."
                    start == null -> error = "Start must be HH:mm (00:00–23:59)."
                    end == null -> error = "End must be HH:mm (00:00–23:59)."
                    start == end -> error = "Start and end can't be equal."
                    else -> onConfirm(daysMask, start, end)
                }
            }) { Text("Add schedule") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun parseMinute(text: String): Int? {
    val parts = text.trim().split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}
