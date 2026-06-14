package com.conversationalai.agent.devicetools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.Settings
import com.conversationalai.agent.core.memory.MemoryStore
import com.conversationalai.agent.core.tools.Tool
import com.conversationalai.agent.core.tools.ToolParam
import com.conversationalai.agent.core.tools.ToolRegistry
import com.conversationalai.agent.core.tools.ToolResult
import com.conversationalai.agent.core.tools.ToolSpec
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Offline, on-device tools for the agentic loop — everything works in airplane mode. Results are
 * short plain English: the MODEL reads them and answers the user in the user's language (results
 * are model-facing, not user-facing).
 *
 * v1 set: clock (time/date), timer + alarm via the system clock app (SET_ALARM normal permission,
 * no runtime prompt), battery status, flashlight.
 * v2 add: calculate (exact offline arithmetic the small model can't do reliably), and durable
 * cross-session memory (remember/recall/forget) backed by [MemoryStore].
 * Side-effecting tools are only reached through SpeechTurnRunner's epoch check, so a barged-in
 * turn never fires them.
 */
object DeviceTools {

    /** Build the registry plus the shared [MemoryStore] (so callers can also inject its snapshot
     *  into the system prompt). */
    fun build(context: Context): Bundle {
        val memory = MemoryStore(File(context.filesDir, "agent_memory/facts.tsv"))
        val registry = ToolRegistry(
            listOf(
                GetDateTime(),
                SetTimer(context),
                SetAlarm(context),
                BatteryStatus(context),
                Flashlight(context),
                Calculate(),
                RememberFact(memory),
                RecallFacts(memory),
                ForgetFact(memory),
            ),
        )
        return Bundle(registry, memory)
    }

    data class Bundle(val registry: ToolRegistry, val memory: MemoryStore)

    /** Back-compat: registry only (tests and callers that don't need the memory handle). */
    fun registry(context: Context): ToolRegistry = build(context).registry

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
            sideEffect = true,
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
            sideEffect = true,
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
            sideEffect = true,
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

    /** Exact offline arithmetic — the model offloads multi-digit math, percentages, etc. */
    private class Calculate : Tool {
        override val spec = ToolSpec(
            name = "calculate",
            description = "evaluate an arithmetic expression exactly (supports + - * / ^, " +
                "parentheses, and percentages like '15% of 84')",
            params = listOf(ToolParam("expression", "the math expression, e.g. '(12.5 + 7) * 3'")),
        )
        override fun execute(args: Map<String, String>): ToolResult {
            val expr = args["expression"]?.takeIf { it.isNotBlank() }
                ?: return ToolResult(false, "missing 'expression'")
            return runCatching { Arithmetic.eval(expr) }.fold(
                onSuccess = { ToolResult(true, "$expr = ${formatNumber(it)}") },
                onFailure = { ToolResult(false, "could not evaluate '$expr': ${it.message}") },
            )
        }

        /** Drop the trailing ".0" on integers; otherwise trim to ~6 significant decimals. */
        private fun formatNumber(v: Double): String {
            if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) return v.toLong().toString()
            return java.math.BigDecimal(v).round(java.math.MathContext(10)).stripTrailingZeros().toPlainString()
        }
    }

    private class RememberFact(private val memory: MemoryStore) : Tool {
        override val spec = ToolSpec(
            name = "remember_fact",
            description = "save a fact about the user durably, so it is available in future " +
                "conversations (e.g. their name, preferences, schedule)",
            params = listOf(
                ToolParam("key", "short label for the fact, e.g. 'name' or 'favorite color'"),
                ToolParam("value", "the fact itself"),
            ),
        )
        override fun execute(args: Map<String, String>): ToolResult {
            val key = args["key"] ?: return ToolResult(false, "missing 'key'")
            val value = args["value"] ?: return ToolResult(false, "missing 'value'")
            return if (memory.remember(key, value)) ToolResult(true, "remembered: $key = $value")
            else ToolResult(false, "'key' and 'value' must both be non-empty")
        }
    }

    private class RecallFacts(private val memory: MemoryStore) : Tool {
        override val spec = ToolSpec(
            name = "recall_facts",
            description = "look up facts saved earlier about the user; omit 'query' to list all",
            params = listOf(ToolParam("query", "what to look for, e.g. 'name'", required = false)),
        )
        override fun execute(args: Map<String, String>): ToolResult {
            val hits = memory.recall(args["query"].orEmpty())
            if (hits.isEmpty()) return ToolResult(true, "no matching facts are saved")
            return ToolResult(true, hits.joinToString("; ") { (k, v) -> "$k: $v" })
        }
    }

    private class ForgetFact(private val memory: MemoryStore) : Tool {
        override val spec = ToolSpec(
            name = "forget_fact",
            description = "delete a saved fact about the user",
            sideEffect = true,
            params = listOf(ToolParam("key", "label of the fact to delete")),
        )
        override fun execute(args: Map<String, String>): ToolResult {
            val key = args["key"] ?: return ToolResult(false, "missing 'key'")
            return if (memory.forget(key)) ToolResult(true, "forgot '$key'")
            else ToolResult(false, "no fact saved under '$key'")
        }
    }
}
