package com.anonymous.droidtunnel

import android.content.Context

object TunnelPreferences {
    const val PREFS_NAME = "droid_tunnel"
    private const val KEY_TOKEN = "token"
    private const val KEY_DESIRED_RUNNING = "desired_running"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readToken(context: Context): String =
        prefs(context).getString(KEY_TOKEN, "").orEmpty()

    fun saveToken(context: Context, token: String) {
        prefs(context)
            .edit()
            .putString(KEY_TOKEN, token.trim())
            .apply()
    }

    fun readDesiredRunning(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DESIRED_RUNNING, false)

    fun setDesiredRunning(context: Context, desiredRunning: Boolean) {
        prefs(context)
            .edit()
            .putBoolean(KEY_DESIRED_RUNNING, desiredRunning)
            .apply()
    }
}
