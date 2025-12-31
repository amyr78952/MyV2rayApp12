package com.example.myv2rayapp.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed class VpnFsmState {
    data object Idle : VpnFsmState()
    data class Starting(val sessionId: String) : VpnFsmState()
    data class Connected(val sessionId: String) : VpnFsmState()
    data class Stopping(val sessionId: String) : VpnFsmState()
    data class Switching(val sessionId: String) : VpnFsmState()
    data class Error(val message: String) : VpnFsmState()
}

object VpnState {

    private const val TAG = "VpnState"

    private const val PREF_NAME = "vpn_state_pref"
    private const val KEY_CONNECTED = "connected"
    private const val KEY_CURRENT_VLESS = "current_vless"
    private const val KEY_CONNECTED_SINCE = "connected_since"

    private const val KEY_FSM_KIND = "fsm_kind"
    private const val KEY_FSM_SESSION = "fsm_session"
    private const val KEY_FSM_ERROR = "fsm_error"

    private const val KEY_CONFIG_NAME = "config_name"
    private const val KEY_GB_LEFT = "gb_left"
    private const val KEY_DAYS_LEFT = "days_left"

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _currentVless = MutableStateFlow<String?>(null)
    val currentVless: StateFlow<String?> = _currentVless

    private val _connectedSince = MutableStateFlow<Long?>(null)
    val connectedSince: StateFlow<Long?> = _connectedSince

    private val _configName = MutableStateFlow<String?>(null)
    val configName: StateFlow<String?> = _configName

    private val _gbLeft = MutableStateFlow<String?>(null)
    val gbLeft: StateFlow<String?> = _gbLeft

    private val _daysLeft = MutableStateFlow<String?>(null)
    val daysLeft: StateFlow<String?> = _daysLeft

    private val _fsm = MutableStateFlow<VpnFsmState>(VpnFsmState.Idle)
    val fsm: StateFlow<VpnFsmState> = _fsm

    fun syncFromPrefs(context: Context) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        _connected.value = sp.getBoolean(KEY_CONNECTED, false)
        _currentVless.value = sp.getString(KEY_CURRENT_VLESS, null)

        val since = sp.getLong(KEY_CONNECTED_SINCE, -1L)
        _connectedSince.value = if (since > 0) since else null

        _configName.value = sp.getString(KEY_CONFIG_NAME, null)
        _gbLeft.value = sp.getString(KEY_GB_LEFT, null)
        _daysLeft.value = sp.getString(KEY_DAYS_LEFT, null)

        val kind = sp.getString(KEY_FSM_KIND, "Idle") ?: "Idle"
        val session = sp.getString(KEY_FSM_SESSION, "") ?: ""
        val err = sp.getString(KEY_FSM_ERROR, "") ?: ""
        _fsm.value = when (kind) {
            "Starting" -> VpnFsmState.Starting(session)
            "Connected" -> VpnFsmState.Connected(session)
            "Stopping" -> VpnFsmState.Stopping(session)
            "Switching" -> VpnFsmState.Switching(session)
            "Error" -> VpnFsmState.Error(err.ifBlank { "error" })
            else -> VpnFsmState.Idle
        }

        Log.d(TAG, "syncFromPrefs: connected=${_connected.value} vless=${_currentVless.value} since=${_connectedSince.value} fsm=${_fsm.value}")
    }

    internal fun setConnected(context: Context, value: Boolean) {
        Log.d(TAG, "setConnected: $value (was=${_connected.value})")
        _connected.value = value
        if (value) {
            if (_connectedSince.value == null) _connectedSince.value = System.currentTimeMillis()
        } else {
            _connectedSince.value = null
        }
        persist(context, "setConnected")
    }

    internal fun setCurrentVless(context: Context, vless: String?) {
        Log.d(TAG, "setCurrentVless: ${vless?.take(64)}")
        _currentVless.value = vless
        persist(context, "setCurrentVless")
    }

    fun setMeta(context: Context, name: String?, gbLeft: String?, daysLeft: String?) {
        Log.d(TAG, "setMeta: name=$name gbLeft=$gbLeft daysLeft=$daysLeft")
        _configName.value = name
        _gbLeft.value = gbLeft
        _daysLeft.value = daysLeft
        persist(context, "setMeta")
    }

    internal fun setFsm(context: Context, newState: VpnFsmState, reason: String) {
        Log.d(TAG, "setFsm: ${_fsm.value} -> $newState reason=$reason")
        _fsm.value = newState
        persist(context, "setFsm(reason=$reason)")
    }

    private fun persist(context: Context, why: String) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val (kind, session, err) = when (val s = _fsm.value) {
            is VpnFsmState.Idle -> Triple("Idle", "", "")
            is VpnFsmState.Starting -> Triple("Starting", s.sessionId, "")
            is VpnFsmState.Connected -> Triple("Connected", s.sessionId, "")
            is VpnFsmState.Stopping -> Triple("Stopping", s.sessionId, "")
            is VpnFsmState.Switching -> Triple("Switching", s.sessionId, "")
            is VpnFsmState.Error -> Triple("Error", "", s.message)
        }

        sp.edit()
            .putBoolean(KEY_CONNECTED, _connected.value)
            .putString(KEY_CURRENT_VLESS, _currentVless.value)
            .putLong(KEY_CONNECTED_SINCE, _connectedSince.value ?: -1L)
            .putString(KEY_CONFIG_NAME, _configName.value)
            .putString(KEY_GB_LEFT, _gbLeft.value)
            .putString(KEY_DAYS_LEFT, _daysLeft.value)
            .putString(KEY_FSM_KIND, kind)
            .putString(KEY_FSM_SESSION, session)
            .putString(KEY_FSM_ERROR, err)
            .apply()

        Log.d(TAG, "persist[$why]: connected=${_connected.value} fsm=$kind/$session err=$err")
    }
}
