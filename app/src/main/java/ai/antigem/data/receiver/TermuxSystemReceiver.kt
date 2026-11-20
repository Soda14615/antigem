package ai.antigem.data.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import ai.antigem.R
import ai.antigem.data.local.TermuxStorageSetupManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

open class TermuxSystemReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        when {
            action.endsWith(".app.request_storage_permissions") || action == "ai.antigem.app.request_storage_permissions" -> {
                if (TermuxStorageSetupManager.hasStoragePermission(context)) {
                    CoroutineScope(Dispatchers.IO).launch {
                        val success = TermuxStorageSetupManager.setupStorageSymlinks(context)
                        CoroutineScope(Dispatchers.Main).launch {
                            Toast.makeText(
                                context,
                                if (success) R.string.notification_storage_symlinks_ready else R.string.notification_storage_symlinks_failed,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } else {
                    TermuxStorageSetupManager.requestStoragePermission(context)
                }
            }
            action.endsWith(".app.reload_style") || action == "ai.antigem.app.reload_style" -> {
                if (TermuxStorageSetupManager.hasStoragePermission(context)) {
                    CoroutineScope(Dispatchers.IO).launch {
                        val success = TermuxStorageSetupManager.setupStorageSymlinks(context)
                        CoroutineScope(Dispatchers.Main).launch {
                            Toast.makeText(
                                context,
                                if (success) R.string.notification_storage_symlinks_ready else R.string.notification_storage_symlinks_failed,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } else {
                    TermuxStorageSetupManager.requestStoragePermission(context)
                }
            }
        }
    }
}
