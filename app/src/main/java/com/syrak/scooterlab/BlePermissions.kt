package com.syrak.scooterlab.core.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Central authority for the BLE runtime-permission matrix.
 *
 *  - API >= 31 : BLUETOOTH_SCAN + BLUETOOTH_CONNECT
 *  - API <= 30 : BLUETOOTH + BLUETOOTH_ADMIN + ACCESS_FINE_LOCATION
 *
 * Keeping this logic in one place means the UI never has to branch on SDK level.
 */
object BlePermissions {

    /** Permissions that must be requested at runtime for the current OS version. */
    val required: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }

    /** True when every required permission is already granted. */
    fun allGranted(context: Context): Boolean = required.all { granted(context, it) }

    /** True when at least one required permission is missing. */
    fun anyMissing(context: Context): Boolean = !allGranted(context)

    /** Human-readable rationale key for the first missing permission (for UI copy). */
    fun missingRationale(context: Context): PermissionRationale? = when {
        !allGranted(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            PermissionRationale.Bluetooth
        !allGranted(context) -> PermissionRationale.Location
        else -> null
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    enum class PermissionRationale { Bluetooth, Location }
}
