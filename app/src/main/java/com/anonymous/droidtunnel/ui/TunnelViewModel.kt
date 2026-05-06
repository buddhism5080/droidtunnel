package com.anonymous.droidtunnel.ui

import android.app.Application
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anonymous.droidtunnel.TunnelPreferences
import com.anonymous.droidtunnel.TunnelScreenState
import com.anonymous.droidtunnel.TunnelService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

class TunnelViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext

    private val tokenState = MutableStateFlow(TunnelPreferences.readToken(appContext))
    private val showTokenEditorState = MutableStateFlow(tokenState.value.isBlank())
    private val batteryOptimizationRecommendedState = MutableStateFlow(isBatteryOptimizationRecommended())

    val uiState: StateFlow<TunnelScreenState> = combine(
        tokenState,
        showTokenEditorState,
        batteryOptimizationRecommendedState,
        TunnelService.runtimeState,
    ) { token, showTokenEditor, batteryOptimizationRecommended, runtime ->
        TunnelScreenState(
            token = token,
            showTokenEditor = showTokenEditor || token.isBlank(),
            batteryOptimizationRecommended = batteryOptimizationRecommended,
            runtime = runtime,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = TunnelScreenState(
            token = tokenState.value,
            showTokenEditor = showTokenEditorState.value,
            batteryOptimizationRecommended = batteryOptimizationRecommendedState.value,
            runtime = TunnelService.runtimeState.value,
        ),
    )

    fun onTokenChanged(token: String) {
        tokenState.value = token
    }

    fun onEditTokenClicked() {
        showTokenEditorState.value = true
    }

    fun onHideTokenEditorClicked() {
        if (tokenState.value.isNotBlank()) {
            showTokenEditorState.value = false
        }
    }

    fun connect() {
        val token = tokenState.value.trim()
        if (token.isBlank()) {
            return
        }

        TunnelPreferences.saveToken(appContext, token)
        showTokenEditorState.value = false
        TunnelService.start(appContext, token)
    }

    fun disconnect() {
        TunnelService.stop(appContext)
    }

    fun refreshBatteryOptimizationState() {
        batteryOptimizationRecommendedState.update { isBatteryOptimizationRecommended() }
    }

    private fun isBatteryOptimizationRecommended(): Boolean {
        val powerManager = appContext.getSystemService(PowerManager::class.java) ?: return false
        return !powerManager.isIgnoringBatteryOptimizations(appContext.packageName)
    }
}
