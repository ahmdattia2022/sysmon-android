package io.ahmed.sysmon.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.ahmed.sysmon.data.entity.BundleCycleEntity
import io.ahmed.sysmon.repo.Repository
import io.ahmed.sysmon.service.RouterPollService
import io.ahmed.sysmon.service.bundle.SmsParsers
import io.ahmed.sysmon.service.router.RouterAdapters
import io.ahmed.sysmon.service.router.RouterKind
import io.ahmed.sysmon.ui.components.PasswordField
import io.ahmed.sysmon.util.Validate
import io.ahmed.sysmon.util.WifiNetworkBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onLoggedOut: () -> Unit = {}) {
    val context = LocalContext.current
    val repo = remember { Repository(context) }
    val scope = rememberCoroutineScope()

    // --- Router form state -------------------------------------------------
    var kind by remember { mutableStateOf(RouterKind.fromPref(repo.prefs.routerKind)) }
    var kindMenuOpen by remember { mutableStateOf(false) }
    var routerBase by remember { mutableStateOf(repo.prefs.routerBase) }
    var routerUser by remember { mutableStateOf(repo.prefs.routerUser) }
    var routerPass by remember { mutableStateOf(repo.prefs.routerPassword) }

    // --- Thresholds --------------------------------------------------------
    var bundleGb by remember { mutableStateOf(repo.prefs.bundleGb.toString()) }
    var hourlyMb by remember { mutableStateOf(repo.prefs.hourlyMbLimit.toString()) }
    var routerHourlyMb by remember { mutableStateOf(repo.prefs.routerHourlyMbLimit.toString()) }
    var routerDailyMb by remember { mutableStateOf(repo.prefs.routerDailyMbLimit.toString()) }
    var emailEnabled by remember { mutableStateOf(repo.prefs.emailEnabled) }
    var saved by remember { mutableStateOf(false) }

    // Validation
    val baseErr = Validate.baseUrl(routerBase)
    val userErr = Validate.notBlank(routerUser, "Username")
    val passErr = Validate.notBlank(routerPass, "Password")
    val bundleErr = Validate.positiveInt(bundleGb, "Bundle GB")
    val hourlyErr = Validate.positiveInt(hourlyMb, "Laptop hourly limit")
    val rtHourlyErr = Validate.positiveInt(routerHourlyMb, "Router hourly limit")
    val rtDailyErr = Validate.positiveInt(routerDailyMb, "Router daily limit")
    val formValid = listOf(baseErr, userErr, passErr, bundleErr, hourlyErr, rtHourlyErr, rtDailyErr).all { it == null }

    // --- Bundle cycle section state ---------------------------------------
    var freshGb by remember { mutableStateOf("140") }
    var topupGb by remember { mutableStateOf("30") }
    var remainingGb by remember { mutableStateOf("") }
    var pastedSms by remember { mutableStateOf("") }
    var bundleResult by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        // ================================================================
        //  Router
        // ================================================================
        SectionHeader("Router")

        ExposedDropdownMenuBox(
            expanded = kindMenuOpen,
            onExpandedChange = { kindMenuOpen = !kindMenuOpen }
        ) {
            OutlinedTextField(
                value = kind.displayName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Model") },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
                supportingText = {
                    if (!kind.adapterAvailable) Text("Falls back to Huawei adapter for now")
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
                            if (routerBase.isBlank()) routerBase = k.suggestedBaseUrl
                        }
                    )
                }
            }
        }

        OutlinedTextField(
            value = routerBase, onValueChange = { routerBase = it },
            label = { Text("Base URL") },
            supportingText = { Text(baseErr ?: "e.g. https://192.168.100.1") },
            isError = baseErr != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = routerUser, onValueChange = { routerUser = it },
            label = { Text("Username") },
            supportingText = userErr?.let { { Text(it) } },
            isError = userErr != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        PasswordField(
            value = routerPass, onValueChange = { routerPass = it },
            label = "Password",
            errorMessage = passErr,
            modifier = Modifier.fillMaxWidth()
        )

        // ================================================================
        //  Thresholds
        // ================================================================
        HorizontalDivider()
        SectionHeader("Thresholds (MB)")

        NumericField("Bundle GB", bundleGb, bundleErr) { bundleGb = it }
        NumericField("Laptop hourly limit (MB)", hourlyMb, hourlyErr) { hourlyMb = it }
        NumericField("Router hourly limit (MB)", routerHourlyMb, rtHourlyErr) { routerHourlyMb = it }
        NumericField("Router daily limit (MB)", routerDailyMb, rtDailyErr) { routerDailyMb = it }

        HorizontalDivider()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Switch(checked = emailEnabled, onCheckedChange = { emailEnabled = it })
            Column {
                Text("Email alerts (optional)", style = MaterialTheme.typography.bodyMedium)
                Text("Requires internet. Off by default.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Button(
            enabled = formValid,
            onClick = {
                repo.prefs.routerKind = kind.name
                repo.prefs.routerBase = routerBase.trim().trimEnd('/')
                repo.prefs.routerUser = routerUser.trim()
                repo.prefs.routerPassword = routerPass
                repo.prefs.bundleGb = bundleGb.toIntOrNull() ?: repo.prefs.bundleGb
                repo.prefs.hourlyMbLimit = hourlyMb.toIntOrNull() ?: repo.prefs.hourlyMbLimit
                repo.prefs.routerHourlyMbLimit = routerHourlyMb.toIntOrNull() ?: repo.prefs.routerHourlyMbLimit
                repo.prefs.routerDailyMbLimit = routerDailyMb.toIntOrNull() ?: repo.prefs.routerDailyMbLimit
                repo.prefs.emailEnabled = emailEnabled
                saved = true
            }
        ) { Text("Save") }

        if (saved) Text("Saved ✓",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary)

        // ================================================================
        //  Bundle cycle
        // ================================================================
        HorizontalDivider()
        SectionHeader("Data bundle")
        Text(
            "Track your ISP bundle here. The dashboard will show remaining GB + days left " +
                "based on the most recent recharge or manual entry.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Fresh recharge
        OutlinedTextField(
            value = freshGb, onValueChange = { freshGb = it },
            label = { Text("Fresh recharge GB") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(
            enabled = Validate.positiveInt(freshGb, "GB") == null,
            onClick = {
                val gb = freshGb.toDoubleOrNull() ?: return@OutlinedButton
                scope.launch {
                    withContext(Dispatchers.IO) {
                        repo.insertCycle(BundleCycleEntity(
                            startDateIso = nowIso(),
                            totalGb = gb,
                            kind = BundleCycleEntity.KIND_MANUAL_FRESH,
                            provider = "WE",
                            note = "Fresh recharge"
                        ))
                    }
                    bundleResult = "Recorded fresh $gb GB recharge ✓"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("I recharged today — $freshGb GB") }

        // Mid-cycle top-up
        OutlinedTextField(
            value = topupGb, onValueChange = { topupGb = it },
            label = { Text("Top-up GB") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(
            enabled = Validate.positiveInt(topupGb, "GB") == null,
            onClick = {
                val gb = topupGb.toDoubleOrNull() ?: return@OutlinedButton
                scope.launch {
                    withContext(Dispatchers.IO) {
                        repo.insertCycle(BundleCycleEntity(
                            startDateIso = nowIso(),
                            totalGb = gb,
                            kind = BundleCycleEntity.KIND_MANUAL_TOPUP,
                            provider = "WE",
                            note = "Mid-cycle top-up"
                        ))
                    }
                    bundleResult = "Added $gb GB top-up ✓ — total cycle GB increased"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Add top-up — $topupGb GB") }

        // Manual remaining (hard reset)
        OutlinedTextField(
            value = remainingGb, onValueChange = { remainingGb = it },
            label = { Text("I currently have GB left") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            supportingText = { Text("Overrides the computed balance at the moment you tap Save.") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(
            enabled = remainingGb.toDoubleOrNull()?.let { it >= 0 } == true,
            onClick = {
                val gb = remainingGb.toDoubleOrNull() ?: return@OutlinedButton
                scope.launch {
                    withContext(Dispatchers.IO) {
                        repo.insertCycle(BundleCycleEntity(
                            startDateIso = nowIso(),
                            // totalGb carries the same value for legacy reasons, but
                            // the math in Repository.bundleBalance() uses
                            // manualRemainingGb as the authoritative override and
                            // keeps the plan total from the FRESH/SMS_AUTO anchor.
                            totalGb = gb,
                            kind = BundleCycleEntity.KIND_MANUAL_REMAINING,
                            provider = "WE",
                            note = "Manual balance check",
                            manualRemainingGb = gb
                        ))
                    }
                    bundleResult = "Balance set to $gb GB ✓ — dashboard will reflect it."
                    remainingGb = ""
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            val label = if (remainingGb.isBlank()) "Set remaining balance"
                        else "Set remaining to $remainingGb GB"
            Text(label)
        }

        // Paste SMS
        OutlinedTextField(
            value = pastedSms, onValueChange = { pastedSms = it },
            label = { Text("Paste recharge SMS (optional)") },
            supportingText = { Text("e.g. the WE / Vodafone message confirming your new bundle.") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(
            enabled = pastedSms.isNotBlank(),
            onClick = {
                val parsed = SmsParsers.parse(pastedSms)
                if (parsed == null) {
                    bundleResult = "Couldn't find a GB amount in that message."
                } else {
                    scope.launch {
                        withContext(Dispatchers.IO) { repo.insertCycle(parsed) }
                        bundleResult = "Parsed ${parsed.totalGb} GB from ${parsed.provider ?: "provider"} SMS ✓"
                        pastedSms = ""
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Parse SMS & add cycle") }

        bundleResult?.let {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    it,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        // ================================================================
        //  Session
        // ================================================================
        HorizontalDivider()
        SectionHeader("Session")
        OutlinedButton(
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val client = RouterAdapters.forKind(
                                kind = RouterKind.fromPref(repo.prefs.routerKind),
                                baseUrl = repo.prefs.routerBase,
                                username = repo.prefs.routerUser,
                                password = repo.prefs.routerPassword,
                                cachedCookie = repo.prefs.sessionCookie.takeIf { it.isNotBlank() }
                            )
                            WifiNetworkBinder.withWifiProcess(context) { client.endSession() }
                                ?: client.endSession()
                        }
                    }
                    RouterPollService.stop(context)
                    repo.prefs.loggedIn = false
                    repo.prefs.clearSession()
                    onLoggedOut()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Log out") }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun NumericField(
    label: String,
    value: String,
    error: String?,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) },
        supportingText = error?.let { { Text(it) } },
        isError = error != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

private fun nowIso(): String =
    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
