package com.anonymous.droidtunnel

import android.content.BroadcastReceiver
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged

class MainActivity : AppCompatActivity() {
    private lateinit var tokenContainer: View
    private lateinit var tokenInput: EditText
    private lateinit var editTokenButton: Button
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var logView: TextView
    private var receiverRegistered = false
    private var notificationPermissionRequested = false

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val logs = intent.getStringExtra(TunnelService.EXTRA_LOGS) ?: return
            logView.text = logs
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tokenContainer = findViewById(R.id.tokenContainer)
        tokenInput = findViewById(R.id.tokenInput)
        editTokenButton = findViewById(R.id.editTokenButton)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        logView = findViewById(R.id.logView)

        val prefs = getSharedPreferences(TunnelService.PREFS_NAME, MODE_PRIVATE)
        val savedToken = prefs.getString(TunnelService.KEY_TOKEN, "") ?: ""
        tokenInput.setText(savedToken)

        if (savedToken.isNotBlank()) {
            tokenContainer.visibility = View.GONE
            editTokenButton.visibility = View.VISIBLE
        } else {
            tokenContainer.visibility = View.VISIBLE
            editTokenButton.visibility = View.GONE
        }

        connectButton.isEnabled = savedToken.isNotBlank()
        tokenInput.doAfterTextChanged { text ->
            connectButton.isEnabled = !text.isNullOrBlank()
        }

        editTokenButton.setOnClickListener {
            tokenContainer.visibility = View.VISIBLE
            editTokenButton.visibility = View.GONE
            tokenInput.requestFocus()
            tokenInput.setSelection(tokenInput.text.length)
        }

        connectButton.setOnClickListener {
            val token = tokenInput.text.toString().trim()
            if (token.isBlank()) {
                Toast.makeText(this, getString(R.string.token_empty), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString(TunnelService.KEY_TOKEN, token).apply()
            tokenContainer.visibility = View.GONE
            editTokenButton.visibility = View.VISIBLE
            TunnelService.start(this, token)
        }

        disconnectButton.setOnClickListener {
            TunnelService.stop(this)
        }

        requestNotificationPermissionIfNeeded()
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

    companion object {
        private const val REQUEST_NOTIFICATIONS = 1002
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (notificationPermissionRequested) {
            return
        }
        notificationPermissionRequested = true
		
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
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
