package com.jarvis.asistente

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/** Botón «Jarvis» en los ajustes rápidos: abre Jarvis ya escuchando. */
class JarvisTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val i = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_LISTEN, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
