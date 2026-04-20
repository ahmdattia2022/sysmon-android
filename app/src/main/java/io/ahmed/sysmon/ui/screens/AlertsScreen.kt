package io.ahmed.sysmon.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ahmed.sysmon.data.entity.AlertEntity
import io.ahmed.sysmon.repo.Repository
import io.ahmed.sysmon.util.Format

@Composable
fun AlertsScreen() {
    val context = LocalContext.current
    val repo = remember { Repository(context) }

    val alerts by repo.alerts.recent(300).collectAsStateWithLifecycle(emptyList())

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Alerts", style = MaterialTheme.typography.headlineMedium,
             fontWeight = FontWeight.Bold)
        Text("${alerts.size} in log", style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))

        if (alerts.isEmpty()) {
            EmptyHint("No alerts yet. Thresholds live in Settings.")
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(alerts, key = { it.id }) { a ->
                    AlertCard(
                        a,
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(220),
                            placementSpec = tween(260),
                            fadeOutSpec = tween(160)
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun AlertCard(a: AlertEntity, modifier: Modifier = Modifier) {
    val (bg, fg, icon) = styleFor(a)
    Card(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(22.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(a.reason,
                     style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold,
                     color = fg)
                Text(
                    buildString {
                        append(Format.hhmm(a.ts))
                        append(" · ")
                        append(Format.ago(a.ts))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = fg.copy(alpha = 0.75f)
                )
                if (!a.error.isNullOrBlank()) {
                    Text(a.error,
                         style = MaterialTheme.typography.bodySmall,
                         color = fg.copy(alpha = 0.85f))
                }
            }
            if (a.valueMb > 0) {
                Text(Format.mb(a.valueMb),
                     style = MaterialTheme.typography.titleMedium,
                     fontWeight = FontWeight.Medium,
                     color = fg)
            }
        }
    }
}

@Composable
private fun styleFor(a: AlertEntity): Triple<androidx.compose.ui.graphics.Color,
        androidx.compose.ui.graphics.Color, ImageVector> = when (a.reason) {
    "ROUTER_POLL_FAIL" -> Triple(
        MaterialTheme.colorScheme.errorContainer,
        MaterialTheme.colorScheme.onErrorContainer,
        Icons.Filled.Error
    )
    "ROUTER_NEW_DEVICE" -> Triple(
        MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.onTertiaryContainer,
        Icons.Filled.NotificationsActive
    )
    "TEST" -> Triple(
        MaterialTheme.colorScheme.surfaceContainerLow,
        MaterialTheme.colorScheme.onSurface,
        Icons.Filled.Sync
    )
    else -> {
        val high = a.reason.startsWith("ROUTER_") || a.reason == "OVER_DAILY_BUDGET"
        if (high) Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Filled.Error
        ) else Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.Filled.CheckCircle
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Text(text,
             modifier = Modifier.padding(16.dp),
             color = MaterialTheme.colorScheme.onSurfaceVariant,
             style = MaterialTheme.typography.bodyMedium)
    }
}
