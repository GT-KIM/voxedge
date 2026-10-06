package com.conversationalai.agent.core

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Feeds the OS thermal status into the conversation controller's [ThermalPolicy]:
 * `PowerManager.addThermalStatusListener` for changes plus the current status at start, and the
 * 10-second thermal headroom forecast as a secondary hint for the event log. No polling.
 */
class ThermalMonitor(
    context: Context,
    private val onStatus: (status: Int, headroom: Float?) -> Unit,
) {
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val listener = PowerManager.OnThermalStatusChangedListener { status -> deliver(status) }
    private var started = false

    fun start() {
        if (started) return
        started = true
        runCatching { pm.addThermalStatusListener(listener) }
            .onFailure { Log.w(TAG, "thermal listener unavailable: ${it.message}") }
        deliver(runCatching { pm.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE))
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { pm.removeThermalStatusListener(listener) }
    }

    /** Current headroom forecast (1.0 = severe throttling expected), or null if unsupported. */
    fun headroom(): Float? =
        runCatching { pm.getThermalHeadroom(HEADROOM_FORECAST_S) }.getOrNull()?.takeIf { !it.isNaN() }

    private fun deliver(status: Int) {
        val headroom = headroom()
        Log.i(TAG, "thermal status=${ThermalPolicy.statusName(status)} headroom=${headroom ?: "n/a"}")
        onStatus(status, headroom)
    }

    companion object {
        private const val TAG = "ThermalMonitor"
        private const val HEADROOM_FORECAST_S = 10
    }
}
