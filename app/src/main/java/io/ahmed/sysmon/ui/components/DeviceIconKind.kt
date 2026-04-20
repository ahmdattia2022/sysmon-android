package io.ahmed.sysmon.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * User-selectable device categories. The `key` is what's persisted on
 * DeviceEntity.iconKind so we never serialize an ImageVector.
 */
enum class DeviceIconKind(val key: String, val label: String, val icon: ImageVector) {
    ROUTER("router", "Router", Icons.Filled.Router),
    PHONE("phone", "Phone", Icons.Filled.Smartphone),
    TABLET("tablet", "Tablet", Icons.Filled.Tablet),
    LAPTOP("laptop", "Laptop", Icons.Filled.Laptop),
    TV("tv", "TV", Icons.Filled.Tv),
    CONSOLE("console", "Console", Icons.Filled.VideogameAsset),
    IOT("iot", "IoT / smart", Icons.Filled.Lightbulb),
    PRINTER("printer", "Printer", Icons.Filled.Print),
    OTHER("other", "Other", Icons.Filled.DevicesOther);

    companion object {
        fun fromKeyOrDefault(key: String?): DeviceIconKind =
            values().firstOrNull { it.key == key } ?: ROUTER
    }
}
