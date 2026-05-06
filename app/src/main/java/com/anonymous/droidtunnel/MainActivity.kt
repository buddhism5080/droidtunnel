package com.anonymous.droidtunnel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anonymous.droidtunnel.ui.TunnelScreen
import com.anonymous.droidtunnel.ui.TunnelViewModel
import com.anonymous.droidtunnel.ui.theme.DroidTunnelTheme

class MainActivity : ComponentActivity() {
    private val viewModel: TunnelViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        if (
            TunnelPreferences.readDesiredRunning(this) &&
            TunnelPreferences.readToken(this).isNotBlank()
        ) {
            TunnelService.ensureRunning(this, "界面已打开")
        }

        setContent {
            DroidTunnelTheme {
                val uiState = viewModel.uiState.collectAsStateWithLifecycle()
                TunnelScreen(
                    state = uiState.value,
                    onTokenChanged = viewModel::onTokenChanged,
                    onConnectClicked = viewModel::connect,
                    onDisconnectClicked = viewModel::disconnect,
                    onEditTokenClicked = viewModel::onEditTokenClicked,
                    onHideTokenEditorClicked = viewModel::onHideTokenEditorClicked,
                    onRequestBatteryOptimizationClicked = ::requestIgnoreBatteryOptimizationsIfNeeded,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshBatteryOptimizationState()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }

        if (
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATIONS,
        )
    }

    private fun requestIgnoreBatteryOptimizationsIfNeeded() {
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            viewModel.refreshBatteryOptimizationState()
            return
        }

        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(intent) }
            .onFailure { viewModel.refreshBatteryOptimizationState() }
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 1002
    }
}
