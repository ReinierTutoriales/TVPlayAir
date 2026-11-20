package com.tvplayair.receiver.control

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import java.util.concurrent.Executors

object HidKeyMapper {
    const val KEY_ENTER: Byte = 0x28
    const val KEY_ESCAPE: Byte = 0x29
    const val KEY_RIGHT_ARROW: Byte = 0x4F
    const val KEY_LEFT_ARROW: Byte = 0x50
    const val KEY_DOWN_ARROW: Byte = 0x51
    const val KEY_UP_ARROW: Byte = 0x52

    // Standard USB HID Boot Keyboard descriptor (8-byte input report).
    val KEYBOARD_REPORT_DESCRIPTOR: ByteArray = byteArrayOf(
        0x05, 0x01, // Usage Page (Generic Desktop)
        0x09, 0x06, // Usage (Keyboard)
        0xA1.toByte(), 0x01, // Collection (Application)
        0x05, 0x07, // Usage Page (Key Codes)
        0x19, 0xE0.toByte(), // Usage Minimum (224)
        0x29, 0xE7.toByte(), // Usage Maximum (231)
        0x15, 0x00, // Logical Minimum (0)
        0x25, 0x01, // Logical Maximum (1)
        0x75, 0x01, // Report Size (1)
        0x95.toByte(), 0x08, // Report Count (8)
        0x81.toByte(), 0x02, // Input (Data, Variable, Absolute)
        0x95.toByte(), 0x01, // Report Count (1)
        0x75, 0x08, // Report Size (8)
        0x81.toByte(), 0x01, // Input (Constant)
        0x95.toByte(), 0x06, // Report Count (6)
        0x75, 0x08, // Report Size (8)
        0x15, 0x00, // Logical Minimum (0)
        0x25, 0x65, // Logical Maximum (101)
        0x05, 0x07, // Usage Page (Key Codes)
        0x19, 0x00, // Usage Minimum (0)
        0x29, 0x65, // Usage Maximum (101)
        0x81.toByte(), 0x00, // Input (Data, Array)
        0xC0.toByte() // End Collection
    )

    fun mapAndroidKeyCode(keyCode: Int): Byte? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> KEY_UP_ARROW
        KeyEvent.KEYCODE_DPAD_DOWN -> KEY_DOWN_ARROW
        KeyEvent.KEYCODE_DPAD_LEFT -> KEY_LEFT_ARROW
        KeyEvent.KEYCODE_DPAD_RIGHT -> KEY_RIGHT_ARROW
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> KEY_ENTER
        KeyEvent.KEYCODE_BACK -> KEY_ESCAPE
        else -> null
    }

    fun buildKeyReport(hidUsage: Byte): ByteArray =
        byteArrayOf(0, 0, hidUsage, 0, 0, 0, 0, 0)

    fun buildReleaseReport(): ByteArray = ByteArray(8)
}

/**
 * Optional, best-effort Bluetooth HID Keyboard bridge for TV remote D-pad navigation.
 * Silently disables itself if the TV lacks Bluetooth HID profile support or permissions;
 * never blocks DACP or media playback.
 */
class TvRemoteHidBridge(context: Context) {
    companion object {
        private const val TAG = "TvRemoteHidBridge"
    }

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TVPlayAir-BTHID").apply { isDaemon = true }
    }

    private var adapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    @Volatile
    private var connectedHost: BluetoothDevice? = null
    @Volatile
    private var available = false

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            available = registered
            if (!registered) connectedHost = null
            Log.i(TAG, "Bluetooth HID app registered=$registered")
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            connectedHost = if (state == BluetoothProfile.STATE_CONNECTED) device else null
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val device = proxy as? BluetoothHidDevice ?: return
            hidDevice = device
            if (!hasBluetoothConnectPermission()) {
                available = false
                return
            }
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "TVPlayAir Remote",
                "Android TV D-Pad Keyboard",
                "TVPlayAir",
                BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                HidKeyMapper.KEYBOARD_REPORT_DESCRIPTOR
            )
            runCatching {
                device.registerApp(sdp, null, null, executor, hidCallback)
            }.onFailure {
                available = false
                Log.i(TAG, "Bluetooth HID unavailable on this TV; continuing without HID")
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
                connectedHost = null
                available = false
            }
        }
    }

    fun startIfSupported() {
        if (!hasBluetoothConnectPermission()) {
            available = false
            return
        }
        val manager = appContext.getSystemService(BluetoothManager::class.java)
        val btAdapter = manager?.adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            available = false
            return
        }
        adapter = btAdapter
        runCatching {
            val ok = btAdapter.getProfileProxy(
                appContext,
                profileListener,
                BluetoothProfile.HID_DEVICE
            )
            if (!ok) available = false
        }.onFailure {
            available = false
            Log.i(TAG, "Bluetooth HID profile proxy unavailable; degrading silently")
        }
    }

    fun isAvailable(): Boolean = available

    fun isConnected(): Boolean = available && connectedHost != null

    @SuppressLint("MissingPermission")
    fun sendAndroidKey(keyCode: Int): Boolean {
        val host = connectedHost ?: return false
        val device = hidDevice ?: return false
        if (!available || !hasBluetoothConnectPermission()) return false
        val hidUsage = HidKeyMapper.mapAndroidKeyCode(keyCode) ?: return false

        return runCatching {
            val downOk = device.sendReport(host, 0, HidKeyMapper.buildKeyReport(hidUsage))
            val upOk = device.sendReport(host, 0, HidKeyMapper.buildReleaseReport())
            downOk && upOk
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    fun release() {
        val device = hidDevice
        hidDevice = null
        connectedHost = null
        available = false
        if (device != null) {
            if (hasBluetoothConnectPermission()) {
                runCatching { device.unregisterApp() }
            }
            runCatching { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, device) }
        }
        executor.shutdownNow()
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }
}
