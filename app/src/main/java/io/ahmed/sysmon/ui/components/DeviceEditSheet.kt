package io.ahmed.sysmon.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.ahmed.sysmon.data.entity.DeviceEntity
import kotlinx.coroutines.launch

private val DEFAULT_GROUPS = listOf("Parents", "Kids", "Work", "IoT", "Guest")

data class DeviceActionBinding(
    val onBlock: suspend (blocked: Boolean) -> Unit,
    val onApplyQos: suspend (downKbps: Int, upKbps: Int) -> Unit,
    val onAddSchedule: suspend (daysMask: Int, startMin: Int, endMin: Int) -> Unit,
    val isSelfMac: Boolean
)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DeviceEditSheet(
    device: DeviceEntity,
    actions: DeviceActionBinding? = null,
    onDismiss: () -> Unit,
    onSave: suspend (label: String?, group: String?, iconKind: String?, dailyBudgetMb: Int?, monthlyBudgetMb: Int?) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var label by remember(device.mac) { mutableStateOf(device.label ?: "") }
    var group by remember(device.mac) { mutableStateOf(device.group ?: "") }
    var iconKey by remember(device.mac) {
        mutableStateOf(device.iconKind ?: DeviceIconKind.ROUTER.key)
    }
    var dailyBudget by remember(device.mac) {
        mutableStateOf(device.dailyBudgetMb?.toString() ?: "")
    }
    var monthlyBudget by remember(device.mac) {
        mutableStateOf(device.monthlyBudgetMb?.toString() ?: "")
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                device.hostname?.takeIf { it.isNotBlank() } ?: device.mac,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "MAC ${device.mac} · IP ${device.lastIp ?: "—"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = label, onValueChange = { label = it },
                label = { Text("Nickname (optional)") },
                supportingText = { Text("Leave blank to keep the router's hostname") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text("Group", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val allGroups = (DEFAULT_GROUPS + listOfNotNull(device.group).filter { it !in DEFAULT_GROUPS })
                    .distinct()
                for (g in allGroups) {
                    FilterChip(
                        selected = group == g,
                        onClick = { group = if (group == g) "" else g },
                        label = { Text(g) }
                    )
                }
            }
            OutlinedTextField(
                value = group, onValueChange = { group = it },
                label = { Text("Custom group") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text("Icon", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                for (kind in DeviceIconKind.values()) {
                    val selected = iconKey == kind.key
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                        onClick = { iconKey = kind.key }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(kind.icon, null, modifier = Modifier.size(20.dp))
                            Text(kind.label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            Text("Budgets", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = dailyBudget, onValueChange = { dailyBudget = it.filter(Char::isDigit) },
                label = { Text("Daily MB (optional)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                supportingText = { Text("Alert when this device exceeds the cap today.") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = monthlyBudget, onValueChange = { monthlyBudget = it.filter(Char::isDigit) },
                label = { Text("Monthly MB (optional)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (actions != null) {
                HorizontalDivider()
                Text("Router control", style = MaterialTheme.typography.titleSmall)
                if (actions.isSelfMac) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(
                            "This phone (self-MAC). Block / throttle is disabled " +
                                "— it would disconnect the app from the router.",
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                var busy by remember { mutableStateOf(false) }
                var actionResult by remember { mutableStateOf<String?>(null) }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        enabled = !actions.isSelfMac && !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                runCatching { actions.onBlock(true) }
                                    .onSuccess { actionResult = "Blocked ✓" }
                                    .onFailure { actionResult = "Block failed: ${it.message}" }
                                busy = false
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Block") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                runCatching { actions.onBlock(false) }
                                    .onSuccess { actionResult = "Unblocked ✓" }
                                    .onFailure { actionResult = "Unblock failed: ${it.message}" }
                                busy = false
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Unblock") }
                }

                Text("Bandwidth limit", style = MaterialTheme.typography.titleSmall)
                val presets = listOf(
                    Triple("Slow", 512, 256),
                    Triple("Normal", 5120, 1024),
                    Triple("Unlimited", 0, 0)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    for ((name, down, up) in presets) {
                        OutlinedButton(
                            enabled = !busy && !(actions.isSelfMac && down in 1..127),
                            onClick = {
                                scope.launch {
                                    busy = true
                                    runCatching { actions.onApplyQos(down, up) }
                                        .onSuccess { actionResult = "$name applied ↓$down ↑$up kbps ✓" }
                                        .onFailure { actionResult = "QoS failed: ${it.message}" }
                                    busy = false
                                }
                            }
                        ) {
                            Text(if (down == 0) name else "$name (↓$down/↑$up)")
                        }
                    }
                }

                var showScheduleDialog by remember { mutableStateOf(false) }
                OutlinedButton(
                    enabled = !actions.isSelfMac && !busy,
                    onClick = { showScheduleDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add block schedule…") }

                if (showScheduleDialog) {
                    BlockScheduleDialog(
                        deviceName = device.label ?: device.hostname ?: device.mac,
                        onDismiss = { showScheduleDialog = false },
                        onConfirm = { days, start, end ->
                            scope.launch {
                                busy = true
                                runCatching { actions.onAddSchedule(days, start, end) }
                                    .onSuccess { actionResult = "Schedule added ✓" }
                                    .onFailure { actionResult = "Schedule failed: ${it.message}" }
                                busy = false
                            }
                            showScheduleDialog = false
                        }
                    )
                }

                actionResult?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.secondary)
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        scope.launch {
                            onSave(
                                label.trim().takeIf { it.isNotBlank() },
                                group.trim().takeIf { it.isNotBlank() },
                                iconKey,
                                dailyBudget.toIntOrNull()?.takeIf { it > 0 },
                                monthlyBudget.toIntOrNull()?.takeIf { it > 0 }
                            )
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Save") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
