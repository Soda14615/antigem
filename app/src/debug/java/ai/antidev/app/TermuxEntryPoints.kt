package ai.antidev.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ai.antigem.data.receiver.TermuxOpenActivity as CoreOpenActivity
import ai.antigem.data.receiver.TermuxOpenReceiver as CoreOpenReceiver
import ai.antigem.data.receiver.TermuxSystemReceiver as CoreSystemReceiver
import ai.antigem.data.service.AntiGemService

/** Android component names expected by the native Termux scripts for this package ID. */
class TermuxService : AntiGemService()
class TermuxOpenActivity : CoreOpenActivity()
class TermuxUrlOpenActivity : CoreOpenActivity()
class TermuxSystemReceiver : CoreSystemReceiver()
class TermuxOpenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent != null) CoreOpenReceiver.handleOpen(context, intent)
    }
}
