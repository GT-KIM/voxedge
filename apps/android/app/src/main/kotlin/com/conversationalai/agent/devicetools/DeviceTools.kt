package com.conversationalai.agent.devicetools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.Settings
import com.conversationalai.agent.core.tools.Tool
import com.conversationalai.agent.core.tools.ToolParam
import com.conversationalai.agent.core.tools.ToolRegistry
import com.conversationalai.agent.core.tools.ToolResult
import com.conversationalai.agent.core.tools.ToolSpec
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Offline, on-device tools for the agentic loop — everything works in airplane mode. Results are
 * short plain English: the MODEL reads them and answers the user in the user's language (results
 * are model-facing, not user-facing).
 *
 * v1 set: clock (time/date), timer + alarm via the system clock app (SET_ALARM normal permission,
 * no runtime prompt), battery status, flashlight. Side-effecting tools are only reached through
 * SpeechTurnRunner's epoch check, so a barged-in turn never fires them.
 */
object DeviceTools {

    fun registry(context: Context): ToolRegistry = ToolRegistry(
        listOf(
            GetDateTime(),
            SetTimer(context),
            SetAlarm(context),
            BatteryStatus(context),
            Flashlight(context),
        ),
    )

    private class GetDateTime : Tool {
        override val spec = ToolSpec(
            name = "get_datetime",
            description = "current local date, time, and weekday",
        )
        override fun execute(args: Map<String, String>): ToolResult {
            val now = LocalDateTime.now()
            // Spoken-friendly 12-hour time: a bare "22:01" tends to be read out as "2201".
            val text = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd (EEEE), h:mm a"))
            return ToolResult(true, "current local datetime: $text")
        }
    }

    private class SetTimer(private val context: Context) : Tool {
        override val spec = ToolSpec(
            name = "set_timer",
            description = "start a countdown timer in the system clock app",
            params = listOf(
                ToolParam("minutes", "duration in minutes (integer)"),
                ToolParam("label", "what the timer is for", required = false),
            ),
        )
        override fun execute(args: Map<String, String>): ToolResult {
            val minutes = args["minutes"]?.toDoubleOrNull()?.toInt()
                ?: return ToolResult(false, "missing or invalid 'minutes'")
            if (minutes !in 1..24 * 60) return ToolResult(false, "'minutes' must be 1..1440")
            val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            args["label"]?.takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            context.startActivity(intent)
            return ToolResult(true, "timer started for $minutes minute(s)")
        }
    }

    private class SetAlarm(private val context: Context) : Tool {
        override val spec = ToolSpec(
            name = "set_alarm",
            description = "set an alarm in the system clock app",
            params = listOf(
                ToolParam("hour", "hour 0-23"),
                ToolParam("minute", "minute 0-59"),
                ToolParam("label", "what the alarm is for", required = false),
            ),
        )
        override fun execute(args: Map<String, String>): ToolResult {
            val hour = args["hour"]?.toDoubleOrNull()?.toInt()
            val minute = args["minute"]?.toDoubleOrNull()?.toInt()
            if (hour == null || hour !in 0..23 || minute == null || minute !in 0..59) {
                return ToolResult(false, "need 'hour' 0-23 and 'minute' 0-59")
            }
            val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            args["label"]?.takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            context.startActivity(intent)
            return ToolResult(true, "alarm set for %02d:%02d".format(hour, minute))
        }
    }

    private class BatteryStatus(private val context: Context) : Tool {
        override val spec = ToolSpec(
            name = "battery_status",
            description = "battery level and charging state, plus airplane-mode state",
        )
        override fun execute(args: Map<String, String>): ToolResult {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return ToolResult(false, "battery state unavailable")
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val airplane = Settings.Global.getInt(
                context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0,
            ) == 1
            return ToolResult(
                true,
                "battery $pct%, " + (if (charging) "charging" else "not charging") +
                    ", airplane mode " + (if (airplane) "on" else "off"),
            )
        }
    }

    private class Flashlight(private val context: Context) : Tool {
        override val spec = ToolSpec(
            name = "flashlight",
            description = "turn the phone flashlight on or off",
            params = listOf(ToolParam("state", "'on' or 'off'")),
        )
        override fun execute(args: Map<String, String>): ToolResult {
            val on = when (args["state"]?.lowercase()) {
                "on", "true", "1" -> true
                "off", "false", "0" -> false
                else -> return ToolResult(false, "'state' must be 'on' or 'off'")
            }
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { cameraId ->
                cm.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ToolResult(false, "no flashlight on this device")
            cm.setTorchMode(id, on)
            return ToolResult(true, "flashlight " + if (on) "on" else "off")
        }
    }
}
