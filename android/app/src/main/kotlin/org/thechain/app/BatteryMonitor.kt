package org.thechain.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import org.thechain.battery.Sample
import org.thechain.node.PhoneNode

/**
 * Reads [BatteryManager] and pushes level into [PhoneNode] for drain fitting (P10 path).
 */
object BatteryMonitor {
    private const val TAG = "ChainBattery"
    private const val MAX_SAMPLES = 120

    fun readLevel(context: Context): Pair<Double, Boolean> {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val levelRaw = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val level = if (levelRaw >= 0 && scale > 0) {
            levelRaw.toDouble() / scale.toDouble()
        } else {
            val pct = context.getSystemService(BatteryManager::class.java)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (pct in 0..100) pct / 100.0 else 1.0
        }
        return level.coerceIn(0.0, 1.0) to charging
    }

    fun applyToNode(context: Context, node: PhoneNode, nowS: Double) {
        val (level, charging) = readLevel(context)
        node.batteryLevel = level
        node.batterySamples.add(Sample(t = nowS, level = level, charging = charging))
        while (node.batterySamples.size > MAX_SAMPLES) {
            node.batterySamples.removeAt(0)
        }
        // Sparse log for later P10 drain analysis — not every tick.
        if (node.batterySamples.size % 20 == 1) {
            Log.i(
                TAG,
                "drain_sample t=$nowS level=$level charging=$charging samples=${node.batterySamples.size}",
            )
        }
    }
}
