package io.ahmed.sysmon.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ahmed.sysmon.data.entity.JobEntity
import io.ahmed.sysmon.repo.Repository
import io.ahmed.sysmon.service.RouterPollService
import io.ahmed.sysmon.util.Format

@Composable
fun JobsScreen() {
    val context = LocalContext.current
    val repo = remember { Repository(context) }
    val jobs by repo.jobs.observeAll().collectAsStateWithLifecycle(emptyList())

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Background jobs", style = MaterialTheme.typography.headlineMedium,
             fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (jobs.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Text("No status yet — awaiting first poll cycle.",
                     modifier = Modifier.padding(16.dp),
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(jobs, key = { it.name }) { j -> JobCard(j) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { RouterPollService.start(context) },
                modifier = Modifier.weight(1f)) {
                Text("Restart poller")
            }
            OutlinedButton(onClick = { RouterPollService.stop(context) },
                modifier = Modifier.weight(1f)) {
                Text("Stop poller")
            }
        }
    }
}

@Composable
private fun JobCard(j: JobEntity) {
    val ok = (j.lastStatus ?: "").startsWith("ok")
    val statusColor = if (ok) MaterialTheme.colorScheme.secondary
        else MaterialTheme.colorScheme.error

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(j.name, style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.SemiBold)
            Text(
                j.lastStatus ?: "—",
                style = MaterialTheme.typography.bodySmall,
                color = statusColor
            )
            Text("last ${Format.ago(j.lastRun)} · next ${Format.ago(j.nextRun)}",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
