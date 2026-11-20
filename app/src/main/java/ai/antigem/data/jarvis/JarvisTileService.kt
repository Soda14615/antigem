package ai.antigem.data.jarvis

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import ai.antigem.MainActivity
import ai.antigem.R

/**
 * The "Jarvis" Quick Settings tile: one tap talks to Jarvis from anywhere. While hands-free mode runs it starts
 * listening right away (screen off works); otherwise it opens the app and starts listening there.
 */
class JarvisTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        JarvisPreferences.init(this)
        qsTile?.apply {
            state = if (JarvisPreferences.enabled.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Jarvis"
            if (Build.VERSION.SDK_INT >= 29) subtitle = getString(when {
                JarvisPreferences.handsFree.value -> R.string.notification_jarvis_tile_hands_free
                JarvisPreferences.enabled.value -> R.string.notification_jarvis_tile_tap_talk
                else -> R.string.notification_jarvis_tile_off
            })
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        JarvisPreferences.init(this)
        if (JarvisController.isAttached && JarvisPreferences.enabled.value && JarvisPreferences.handsFree.value) {
            runCatching { startService(Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_TALK)) }
            return
        }
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_TALK, JarvisPreferences.enabled.value)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 77, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** MainActivity starts listening when it is opened with this extra. */
        const val EXTRA_TALK = "ai.antigem.jarvis.EXTRA_TALK"
    }
}
