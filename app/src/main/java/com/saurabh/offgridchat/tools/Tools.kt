package com.saurabh.offgridchat.tools

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Dono phones me same UUID hona chahiye. Isse hi pata chalta hai ki device OffGrid chalata hai. */
val APP_UUID: UUID = UUID.fromString("8f3c2a10-5b7e-4d1a-9c64-0f6a1d2b7e55")
const val SERVICE_NAME = "OffGridChat"

/* ---------------- Models ---------------- */

data class BtDevice(
    val name: String,
    val address: String,
    val rssi: Int? = null,
    val capable: Boolean? = null // null = abhi check ho raha hai
)

data class ChatMessage(
    val id: Long = System.nanoTime(),
    val messageId: String = UUID.randomUUID().toString(),
    val address: String,
    val nodeId: String? = null,
    val text: String,
    val fromMe: Boolean,
    val time: Long = System.currentTimeMillis(),
    val status: String = "SENT" // SENDING, SENT, DELIVERED, READ, FAILED
)

sealed interface ConnState {
    object Idle : ConnState
    data class Connecting(val address: String) : ConnState
    data class Connected(val address: String, val name: String) : ConnState
    data class Failed(val message: String, val at: Long = System.nanoTime()) : ConnState
}

/* ---------------- Small helpers ---------------- */

fun formatTime(ms: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

fun signalLabel(rssi: Int): String = when {
    rssi > -60 -> "Strong"
    rssi > -80 -> "Good"
    else -> "Weak"
}

object BtPermissions {
    val required: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun granted(ctx: Context): Boolean = required.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    fun Intent.btDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33)
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else
            @Suppress("DEPRECATION") getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
}
