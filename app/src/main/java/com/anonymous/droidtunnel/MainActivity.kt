package com.anonymous.droidtunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged

class MainActivity : AppCompatActivity() {
    private lateinit var tokenInput: EditText
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var logView: TextView
    private var receiverRegistered = false

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val logs = intent.getStringExtra(TunnelService.EXTRA_LOGS) ?: return
            logView.text = logs
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tokenInput = findViewById(R.id.tokenInput)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        logView = findViewById(R.id.logView)

        val prefs = getSharedPreferences(TunnelService.PREFS_NAME, MODE_PRIVATE)
        val savedToken = prefs.getString(TunnelService.KEY_TOKEN, "") ?: ""
        tokenInput.setText(savedToken)

        connectButton.isEnabled = savedToken.isNotBlank()
        tokenInput.doAfterTextChanged { text ->
            connectButton.isEnabled = !text.isNullOrBlank()
        }

        connectButton.setOnClickListener {
            val token = tokenInput.text.toString().trim()
            if (token.isBlank()) {
                Toast.makeText(this, getString(R.string.token_empty), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString(TunnelService.KEY_TOKEN, token).apply()
            TunnelService.start(this, token)
        }

        disconnectButton.setOnClickListener {
            TunnelService.stop(this)
        }

        requestIgnoreBatteryOptimizationsIfNeeded()

        if (savedToken.isNotBlank()) {
            TunnelService.start(this, savedToken)
        }
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter(TunnelService.ACTION_LOG)
            ContextCompat.registerReceiver(
                this,
                logReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        logView.text = TunnelService.getLogSnapshot()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(logReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun requestIgnoreBatteryOptimizationsIfNeeded() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }
}
