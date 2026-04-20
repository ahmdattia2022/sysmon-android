package io.ahmed.sysmon

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.ahmed.sysmon.repo.Repository
import io.ahmed.sysmon.service.RouterPollService
import io.ahmed.sysmon.ui.AppNav
import io.ahmed.sysmon.ui.theme.SysmonTheme

class MainActivity : ComponentActivity() {

    private val requestPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ignore — service runs either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: content draws under the status/nav bars; Scaffold's
        // WindowInsets handle the padding per-screen.
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        askForNotificationPermissionIfNeeded()
        askForBatteryOptExemption()

        // Only nudge the service back up if the user is already logged in.
        if (Repository(this).prefs.loggedIn) {
            RouterPollService.start(this)
        }

        setContent {
            SysmonTheme {
                OnResumeNudge()
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNav()
                }
            }
        }
    }

    /**
     * When the app comes back to the foreground, kick the poll service so the
     * user sees fresh data immediately instead of waiting up to 60 s for the
     * next scheduled tick. No-op if the service isn't running (channel capacity 1).
     */
    @Composable
    private fun OnResumeNudge() {
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val obs = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    RouterPollService.requestRefresh()
                }
            }
            owner.lifecycle.addObserver(obs)
            onDispose { owner.lifecycle.removeObserver(obs) }
        }
    }

    private fun askForNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun askForBatteryOptExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
            runCatching { startActivity(intent) }
        }
    }
}
