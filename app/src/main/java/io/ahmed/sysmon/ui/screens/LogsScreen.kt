package io.ahmed.sysmon.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ahmed.sysmon.data.entity.LogEntry
import io.ahmed.sysmon.repo.Repository
import io.ahmed.sysmon.util.Format

@Composable
fun LogsScreen() {
    val context = LocalContext.current
    val repo = remember { Repository(context) }
    val rows by repo.logs.recent(300).collectAsStateWithLifecycle(emptyList())

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Live trace", style = MaterialTheme.typography.headlineMedium,
             fontWeight = FontWeight.Bold)
        Text("${rows.size} entries · auto-trimmed after 7 days",
             style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))

        if (rows.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Text("No activity yet.",
                     modifier = Modifier.padding(16.dp),
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(rows, key = { it.id }) { e -> LogRow(e) }
            }
        }
    }
}

@Composable
private fun LogRow(e: LogEntry) {
    val (levelColor, levelText) = when (e.level) {
        "ERROR" -> MaterialTheme.colorScheme.error to "ERR"
        "WARN" -> Color(0xFFE6A23C) to "WRN"
        else -> MaterialTheme.colorScheme.primary to "INF"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(levelText, style = MaterialTheme.typography.labelSmall,
                 fontWeight = FontWeight.Bold, color = levelColor)
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(Format.hhmm(e.ts) + ":" + e.ts.substringAfterLast(':'),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         fontFamily = FontFamily.Monospace)
                    Text(e.tag, style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         fontWeight = FontWeight.SemiBold)
                }
                Text(e.message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
