package com.jarvis.asistente

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Auriculares Bluetooth: mientras JANUS escucha una orden usa el micrófono de los auriculares
 * (como Siri con los AirPods) y lo suelta antes de hablar para que la voz suene en estéreo.
 */
object HeadsetMic {
    @Volatile
    private var active = false

    fun start(ctx: Context): Boolean {
        if (active) return true
        val am = ctx.getSystemService(AudioManager::class.java)
        return try {
            if (Build.VERSION.SDK_INT >= 31) {
                val dev = am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT >= 33 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
                } ?: return false
                active = am.setCommunicationDevice(dev)
            } else {
                val connected = am.getDevices(AudioManager.GET_DEVICES_INPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                if (!connected || !am.isBluetoothScoAvailableOffCall) return false
                @Suppress("DEPRECATION")
                am.startBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = true
                active = true
            }
            active
        } catch (_: Exception) {
            false
        }
    }

    fun stop(ctx: Context) {
        if (!active) return
        active = false
        val am = ctx.getSystemService(AudioManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            else {
                @Suppress("DEPRECATION")
                am.stopBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = false
            }
        } catch (_: Exception) {
        }
    }
}
