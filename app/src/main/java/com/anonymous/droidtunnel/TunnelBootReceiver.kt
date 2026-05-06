package com.anonymous.droidtunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TunnelBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                val token = TunnelPreferences.readToken(context)
                val desiredRunning = TunnelPreferences.readDesiredRunning(context)
                if (desiredRunning && token.isNotBlank()) {
                    TunnelService.ensureRunning(
                        context,
                        "系统事件: ${intent.action.orEmpty()}",
                    )
                }
            }
        }
    }
}
