package com.rnalarmmodule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.AlarmManager
import android.app.PendingIntent
import android.os.Build
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val PREFS = "rn_alarm_module_alarms"
        private const val DATE_PATTERN = "yyyy-MM-dd'T'HH:mm:ss"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                Log.d(TAG, "Rescheduling alarms after: ${intent.action}")
                rescheduleAlarms(context)
            }
        }
    }

    private fun rescheduleAlarms(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = prefs.all
        if (all.isEmpty()) {
            Log.d(TAG, "No alarms to reschedule.")
            return
        }

        val sdf = SimpleDateFormat(DATE_PATTERN, Locale.getDefault())
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()

        var rescheduledCount = 0
        var skippedCount = 0

        for ((id, rawJson) in all) {
            val jsonStr = rawJson as? String ?: continue
            val obj = try {
                JSONObject(jsonStr)
            } catch (_: Exception) {
                continue
            }
            val datetimeISO = obj.optString("datetimeISO", "")
            val title = obj.optString("title", "Alarm")
            val body = obj.optString("body", "")
            val snoozeEnabled = obj.optBoolean("snoozeEnabled", true)
            val snoozeInterval = obj.optInt("snoozeInterval", 5)
            val repeatFrequency = obj.optInt("repeatFrequency", -1)

            val date = try {
                sdf.parse(datetimeISO)
            } catch (_: Exception) {
                null
            } ?: continue
            
            val triggerAt = date.time
            
            // For recurring alarms with past trigger times, calculate next occurrence
            var finalTriggerAt = triggerAt
            if (triggerAt <= now && repeatFrequency >= 0) {
                val intervalMillis = AlarmModule.getRepeatIntervalMillis(repeatFrequency)
                // Calculate how many intervals have passed and get the next future occurrence
                val intervalsPassed = ((now - triggerAt) / intervalMillis) + 1
                finalTriggerAt = triggerAt + (intervalsPassed * intervalMillis)
                Log.d(TAG, "Recurring alarm id=$id: original time in past, rescheduled to next occurrence")
            } else if (triggerAt <= now) {
                // Past one-time alarms: skip
                skippedCount++
                continue
            }

            val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra("id", id)
                putExtra("title", title)
                putExtra("body", body)
                putExtra("snoozeEnabled", snoozeEnabled)
                putExtra("snoozeInterval", snoozeInterval)
                putExtra("repeatFrequency", repeatFrequency)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                id.hashCode(),
                alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Use repeating or exact alarm based on repeatFrequency
            if (repeatFrequency >= 0) {
                val intervalMillis = AlarmModule.getRepeatIntervalMillis(repeatFrequency)
                alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, finalTriggerAt, intervalMillis, pendingIntent)
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, finalTriggerAt, pendingIntent)
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, finalTriggerAt, pendingIntent)
                }
            }

            rescheduledCount++
            Log.d(TAG, "Rescheduled alarm id=$id at=$datetimeISO")
        }

        Log.d(TAG, "Rescheduling complete: $rescheduledCount rescheduled, $skippedCount skipped (past)")
    }
}