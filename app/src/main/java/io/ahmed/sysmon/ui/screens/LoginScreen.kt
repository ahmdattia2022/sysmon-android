package io.ahmed.sysmon.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ahmed.sysmon.repo.Repository
import io.ahmed.sysmon.service.RouterPollService
import io.ahmed.sysmon.service.router.RouterAdapters
import io.ahmed.sysmon.service.router.RouterKind
import io.ahmed.sysmon.ui.components.PasswordField
import io.ahmed.sysmon.util.Logger
import io.ahmed.sysmon.util.Validate
import io.ahmed.sysmon.util.WifiNetworkBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { Repository(context) }
    val scope = rememberCoroutineScope()

    var kind by remember { mutableStateOf(RouterKind.fromPref(repo.prefs.routerKind)) }
    var kindMenuOpen by remember { mutableStateOf(false) }
    var base by remember { mutableStateOf(repo.prefs.routerBase) }
    var user by remember { mutableStateOf(repo.prefs.routerUser) }
    var pass by remember { mutableStateOf(repo.prefs.routerPassword) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val baseError = Validate.baseUrl(base)
    val userError = Validate.notBlank(user, "Username")
    val passError = Validate.notBlank(pass, "Password")
    val formValid = baseError == null && userError == null && passError == null

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            Icons.Filled.Router, null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp).align(Alignment.CenterHorizontally)
        )
        Text(
            "Sign in to your router",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Text(
            "Session stays active until you log out. Zero data leaves your home network.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(Modifier.height(4.dp))

        // Router model dropdown
        ExposedDropdownMenuBox(
            expanded = kindMenuOpen,
            onExpandedChange = { kindMenuOpen = !kindMenuOpen }
        ) {
            OutlinedTextField(
                value = kind.displayName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Router model") },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
                supportingText = {
                    if (!kind.adapterAvailable) Text("Adapter not shipped yet — falls back to Huawei behavior")
                },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = kindMenuOpen,
                onDismissRequest = { kindMenuOpen = false }
            ) {
                for (k in RouterKind.values()) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(k.displayName)
                                if (!k.adapterAvailable) {
                                    Text("coming soon",
                                         style = MaterialTheme.typography.labelSmall,
                                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        onClick = {
                            kind = k
                            kindMenuOpen = false
                            // Auto-fill suggested base URL when switching models if
                            // the user hasn't customised it.
                            if (base.isBlank() || base == repo.prefs.routerBase) {
                                base = k.suggestedBaseUrl
                            }
                        }
                    )
                }
            }
        }

        OutlinedTextField(
            value = base, onValueChange = { base = it },
            label = { Text("Router URL") },
            supportingText = {
                Text(baseError ?: "e.g. https://192.168.100.1")
            },
            isError = baseError != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = user, onValueChange = { user = it },
            label = { Text("Username") },
            supportingText = userError?.let { { Text(it) } },
            isError = userError != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        PasswordField(
            value = pass, onValueChange = { pass = it },
            label = "Password",
            errorMessage = passError,
            modifier = Modifier.fillMaxWidth()
        )

        error?.let {
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ), shape = RoundedCornerShape(12.dp)) {
                Text(
                    it,
                    modifier = Modifier.padding(14.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Button(
            onClick = {
                error = null
                busy = true
                // Persist chosen kind up-front so the poll service starts on the right adapter.
                repo.prefs.routerKind = kind.name
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            WifiNetworkBinder.ensureStarted(context)
                            Logger.i(context, "login", "user tapped Sign in (kind=${kind.name}, base=${base.trim()})")
                            val client = RouterAdapters.forKind(
                                kind = kind,
                                baseUrl = base.trim().trimEnd('/'),
                                username = user.trim(),
                                password = pass,
                                onSessionEstablished = { sid ->
                                    repo.prefs.sessionCookie = sid
                                    repo.prefs.sessionEstablishedAt = System.currentTimeMillis()
                                    Logger.i(context, "login", "session cookie received")
                                },
                                trace = { line -> Logger.i(context, "client", line) }
                            )
                            WifiNetworkBinder.withWifiProcess(context) { client.collect() }
                                ?: client.collect()
                        }
                    }
                    busy = false
                    if (result.isSuccess) {
                        repo.prefs.routerBase = base.trim().trimEnd('/')
                        repo.prefs.routerUser = user.trim()
                        repo.prefs.routerPassword = pass
                        repo.prefs.loggedIn = true
                        RouterPollService.start(context)
                        Toast.makeText(context, "Connected", Toast.LENGTH_SHORT).show()
                        onLoggedIn()
                    } else {
                        val e = result.exceptionOrNull()
                        val sig = "${e?.javaClass?.simpleName}: ${e?.message ?: "Unknown"}"
                        Logger.e(context, "login", sig, e)
                        error = sig
                    }
                }
            },
            enabled = !busy && formValid,
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            if (busy) CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            ) else Text("Sign in & start monitoring")
        }
    }
}
