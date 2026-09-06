package com.cryptomesh.frontend.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

fun requiredBluetoothPermissions(): List<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

fun hasRequiredBluetoothPermissions(context: Context): Boolean {
    return requiredBluetoothPermissions().all {
        ContextCompat.checkSelfPermission(context, it) ==
            PackageManager.PERMISSION_GRANTED
    }
}

@SuppressLint("MissingPermission")
fun isBluetoothEnabled(context: Context): Boolean {
    if (!hasRequiredBluetoothPermissions(context)) return false
    val manager = context.getSystemService(BluetoothManager::class.java)
    return runCatching { manager?.adapter?.isEnabled == true }
        .getOrDefault(false)
}
