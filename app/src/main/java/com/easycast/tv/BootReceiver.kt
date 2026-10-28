package com.easycast.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the DLNA service when the TV boots, so it is already discoverable. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            context.startForegroundService(Intent(context, DlnaService::class.java))
        }
    }
}
