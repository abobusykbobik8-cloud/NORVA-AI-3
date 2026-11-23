package ai.norva.app.platform

import ai.norva.chat.MemoryInfoProvider
import ai.norva.chat.PowerStateProvider
import ai.norva.policy.DeviceMemory
import ai.norva.policy.PowerState
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager

class AndroidPowerStateProvider(private val context: Context) : PowerStateProvider {
    private val powerManager = context.getSystemService(PowerManager::class.java)

    override fun current(): PowerState {
        val battery: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = plugged != 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return PowerState(
            thermalStatus = powerManager.currentThermalStatus,
            batteryPercent = percent,
            charging = charging,
            powerSave = powerManager.isPowerSaveMode,
        )
    }
}

class AndroidMemoryInfoProvider(context: Context) : MemoryInfoProvider {
    private val activityManager = context.getSystemService(ActivityManager::class.java)

    override fun current(): DeviceMemory {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return DeviceMemory(totalBytes = info.totalMem, availBytes = info.availMem)
    }
}
