package com.jarvis.asistente

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock

/**
 * Auriculares (Bluetooth o con cable), como Siri con los AirPods:
 *  - mientras JANUS escucha una orden usa el micrófono de los auriculares;
 *  - espera a que el enlace de micrófono esté de verdad activo antes de grabar (si no, se
 *    pierde el principio de la orden);
 *  - lo suelta antes de hablar y espera a que vuelva el sonido estéreo para no cortar la voz.
 */
object HeadsetMic {
    @Volatile
    private var active = false
    @Volatile
    private var lastStop = 0L
    private var savedMode = AudioManager.MODE_NORMAL

    private val BT_INPUTS: Set<Int> get() = buildSet {
        add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        if (Build.VERSION.SDK_INT >= 31) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    /** ¿Hay auriculares conectados (de cualquier tipo)? */
    fun connected(ctx: Context): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java)
        val types = mutableSetOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET
        )
        if (Build.VERSION.SDK_INT >= 31) { types.add(AudioDeviceInfo.TYPE_BLE_HEADSET); types.add(AudioDeviceInfo.TYPE_BLE_SPEAKER) }
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
    }

    /** Micrófono de unos auriculares con cable (Android ya lo usa solo; no hace falta enlace). */
    fun wiredMic(ctx: Context): AudioDeviceInfo? =
        ctx.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }

    /** Micrófono Bluetooth disponible, para fijarlo como entrada de la grabación. */
    fun btInput(ctx: Context): AudioDeviceInfo? =
        ctx.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type in BT_INPUTS }

    /** ¿Hay unos auriculares Bluetooth con micrófono que se pueda usar ahora? */
    fun btAvailable(ctx: Context): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java)
        return try {
            if (Build.VERSION.SDK_INT >= 31) am.availableCommunicationDevices.any { it.type in BT_INPUTS }
            else am.isBluetoothScoAvailableOffCall && am.getDevices(AudioManager.GET_DEVICES_INPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        } catch (_: Exception) {
            false
        }
    }

    fun start(ctx: Context): Boolean {
        if (active) return true
        val am = ctx.getSystemService(AudioManager::class.java)
        return try {
            if (Build.VERSION.SDK_INT >= 31) {
                val dev = am.availableCommunicationDevices.firstOrNull { it.type in BT_INPUTS } ?: return false
                savedMode = am.mode
                // Muchos auriculares solo abren su micrófono en «modo comunicación».
                try { am.mode = AudioManager.MODE_IN_COMMUNICATION } catch (_: Exception) {}
                active = am.setCommunicationDevice(dev)
                if (!active) try { am.mode = savedMode } catch (_: Exception) {}
            } else {
                val connected = am.getDevices(AudioManager.GET_DEVICES_INPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                if (!connected || !am.isBluetoothScoAvailableOffCall) return false
                savedMode = am.mode
                try { am.mode = AudioManager.MODE_IN_COMMUNICATION } catch (_: Exception) {}
                @Suppress("DEPRECATION")
                am.startBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = true
                active = true
            }
            if (active) Diag.log("Micrófono de los auriculares activado")
            active
        } catch (_: Exception) {
            false
        }
    }

    val isActive: Boolean get() = active

    /**
     * Espera (bloqueando, fuera del hilo principal) a que el micrófono Bluetooth esté
     * conectado de verdad. Devuelve false si no llega a tiempo.
     */
    fun awaitReady(ctx: Context, timeoutMs: Long = 2500): Boolean {
        if (!active) return false
        val am = ctx.getSystemService(AudioManager::class.java)
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            val ok = if (Build.VERSION.SDK_INT >= 31) am.communicationDevice?.let { it.type in BT_INPUTS } == true
            else scoOn(am)
            if (ok) {
                Thread.sleep(250) // el enlace tarda un poco más en dar audio útil
                return true
            }
            Thread.sleep(50)
        }
        Diag.log("El micrófono de los auriculares no respondió a tiempo: uso el del móvil")
        return false
    }

    @Suppress("DEPRECATION")
    private fun scoOn(am: AudioManager) = am.isBluetoothScoOn

    fun stop(ctx: Context) {
        if (!active) return
        active = false
        lastStop = SystemClock.elapsedRealtime()
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
        try { am.mode = if (savedMode == AudioManager.MODE_IN_COMMUNICATION) AudioManager.MODE_NORMAL else savedMode } catch (_: Exception) {}
    }

    /**
     * Al soltar el micrófono, los auriculares tardan un momento en volver al sonido estéreo:
     * si JANUS hablara ya, se perderían sus primeras palabras. Bloquea lo que falte.
     */
    fun waitForStereo() {
        val left = 900 - (SystemClock.elapsedRealtime() - lastStop)
        if (lastStop > 0 && left > 0) Thread.sleep(left)
    }
}
