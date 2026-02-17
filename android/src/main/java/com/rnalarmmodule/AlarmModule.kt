package com.rnalarmmodule

import com.facebook.react.bridge.*
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.text.SimpleDateFormat
import java.util.*
import android.util.Log
import org.json.JSONObject
import android.content.pm.PackageManager
import java.lang.ref.WeakReference

class AlarmModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "AlarmModule"
        private const val PREFS = "rn_alarm_module_alarms"
        private const val DATE_PATTERN = "yyyy-MM-dd'T'HH:mm:ss"

        // Weak reference to avoid memory leaks - used by AlarmReceiver to emit events
        private var reactContextRef: WeakReference<ReactApplicationContext>? = null

        fun getReactContext(): ReactApplicationContext? = reactContextRef?.get()

        /**
         * Get interval in milliseconds for repeat frequency
         */
        fun getRepeatIntervalMillis(repeatFrequency: Int): Long {
            return when (repeatFrequency) {
                0 -> AlarmManager.INTERVAL_HOUR  // HOURLY
                1 -> AlarmManager.INTERVAL_DAY   // DAILY
                2 -> AlarmManager.INTERVAL_DAY * 7  // WEEKLY
                else -> AlarmManager.INTERVAL_DAY
            }
        }
    }

    init {
        // Update the reference whenever the module is instantiated
        reactContextRef = WeakReference(reactContext)
    }

    override fun getName(): String = "AlarmModule"

    @ReactMethod
    fun getCurrentAlarmPlaying(promise: Promise) {
        if (AlarmReceiver.activeAlarmId != null) {
            val map = Arguments.createMap()
            map.putString("activeAlarmId", AlarmReceiver.activeAlarmId)
            promise.resolve(map)
        } else {
            promise.resolve(null)
        }
    }

    @ReactMethod
    fun stopCurrentAlarm(id: String, promise: Promise) {
        try {
            val intent = Intent(reactApplicationContext, AlarmReceiver::class.java).apply {
                action = AlarmReceiver.ACTION_STOP
                putExtra("id", id)
            }
            reactApplicationContext.sendBroadcast(intent)
            promise.resolve(null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop alarm: ${e.message}")
            promise.reject("STOP_ERROR", e)
        }
    }

    @ReactMethod
    fun snoozeCurrentAlarm(id: String, minutes: Int, promise: Promise) {
        try {
            val alarm = getAlarm(id) ?: throw Exception("Alarm not found")
            val title = alarm.optString("title", "Alarm")
            val body = alarm.optString("body", "")
            val snoozeEnabled = alarm.optBoolean("snoozeEnabled", true)
            val snoozeInterval = alarm.optInt("snoozeInterval", 5)
            val repeatFrequency = alarm.optInt("repeatFrequency", -1)

            val intent = Intent(reactApplicationContext, AlarmReceiver::class.java).apply {
                action = AlarmReceiver.ACTION_SNOOZE
                putExtra("id", id)
                putExtra("title", title)
                putExtra("body", "$body (Snoozed)")
                putExtra("snoozeMinutes", minutes)
                putExtra("snoozeEnabled", snoozeEnabled)
                putExtra("snoozeInterval", snoozeInterval)
                putExtra("repeatFrequency", repeatFrequency)
            }
            reactApplicationContext.sendBroadcast(intent)
            promise.resolve(null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to snooze alarm: ${e.message}")
            promise.reject("SNOOZE_ERROR", e)
        }
    }

    @ReactMethod
    fun requestPermissions(promise: Promise) {
        val granted = if (Build.VERSION.SDK_INT >= 33) {
            reactApplicationContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        val map = Arguments.createMap()
        map.putBoolean("granted", granted)
        promise.resolve(map)
    }

    @ReactMethod
    fun scheduleAlarm(alarm: ReadableMap, promise: Promise) {
        try {
            val id = alarm.getString("id") ?: UUID.randomUUID().toString()
            val datetimeISO = alarm.getString("datetimeISO") ?: throw Exception("datetimeISO required")
            val title = alarm.getString("title") ?: "Alarm"
            val body = alarm.getString("body") ?: ""
            val snoozeEnabled = if (alarm.hasKey("snoozeEnabled")) alarm.getBoolean("snoozeEnabled") else true
            val snoozeInterval = if (alarm.hasKey("snoozeInterval")) alarm.getInt("snoozeInterval") else 5
            val repeatFrequency = if (alarm.hasKey("repeatFrequency")) alarm.getInt("repeatFrequency") else -1

            val sdf = SimpleDateFormat(DATE_PATTERN, Locale.getDefault())
            val date = sdf.parse(datetimeISO) ?: throw Exception("Invalid date format")
            val triggerAt = date.time

            val now = System.currentTimeMillis()
            if (triggerAt < now) {
                Log.w(TAG, "scheduleAlarm: trigger time in past ($datetimeISO); scheduling anyway.")
            }

            val alarmManager = reactApplicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val alarmIntent = Intent(reactApplicationContext, AlarmReceiver::class.java).apply {
                putExtra("id", id)
                putExtra("title", title)
                putExtra("body", body)
                putExtra("snoozeEnabled", snoozeEnabled)
                putExtra("snoozeInterval", snoozeInterval)
                putExtra("repeatFrequency", repeatFrequency)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                reactApplicationContext,
                id.hashCode(),
                alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Use setAlarmClock for exact alarms (both one-time and repeating)
            // For repeating alarms, we'll reschedule in AlarmReceiver after each trigger
            // Create a PendingIntent for the alarm clock info (shown to user)
            val showIntent = reactApplicationContext.packageManager.getLaunchIntentForPackage(reactApplicationContext.packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("alarm_id", id)
            }
            
            val showPendingIntent = if (showIntent != null) {
                PendingIntent.getActivity(
                    reactApplicationContext,
                    id.hashCode() + 999,
                    showIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                // Fallback if we can't get launch intent
                pendingIntent
            }
            
            val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerAt, showPendingIntent)
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)

            saveAlarm(id, datetimeISO, title, body, snoozeEnabled, snoozeInterval, repeatFrequency)
            Log.d(TAG, "Scheduled alarm id=$id at=$datetimeISO repeatFrequency=$repeatFrequency")
            promise.resolve(null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule alarm: ${e.message}")
            promise.reject("SCHEDULE_ERROR", e)
        }
    }

    @ReactMethod
    fun cancelAlarm(id: String, promise: Promise) {
        try {
            val intent = Intent(reactApplicationContext, AlarmReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                reactApplicationContext,
                id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = reactApplicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pi)
            removeAlarm(id)
            Log.d(TAG, "Cancelled alarm id=$id")
            promise.resolve(null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel alarm: ${e.message}")
            promise.reject("CANCEL_ERROR", e)
        }
    }

    @ReactMethod
    fun listAlarms(promise: Promise) {
        val arr = Arguments.createArray()
        val prefs = reactApplicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = prefs.all
        for ((id, rawJson) in all) {
            val jsonStr = rawJson as? String ?: continue
            val obj = try {
                JSONObject(jsonStr)
            } catch (_: Exception) {
                continue
            }
            val map = Arguments.createMap()
            map.putString("id", id)
            map.putString("datetimeISO", obj.optString("datetimeISO"))
            map.putString("title", obj.optString("title"))
            map.putString("body", obj.optString("body"))
            map.putBoolean("snoozeEnabled", obj.optBoolean("snoozeEnabled", true))
            map.putInt("snoozeInterval", obj.optInt("snoozeInterval", 5))
            map.putInt("repeatFrequency", obj.optInt("repeatFrequency", -1))
            arr.pushMap(map)
        }
        promise.resolve(arr)
    }

    @ReactMethod
    fun snoozeAlarm(message: String, snoozeMinutes: Int, promise: Promise) {
        try {
            val id = UUID.randomUUID().toString()
            val alarmIntent = Intent(reactApplicationContext, AlarmReceiver::class.java).apply {
                putExtra("id", id)
                putExtra("title", "Snoozed Alarm")
                putExtra("body", message)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                reactApplicationContext,
                id.hashCode(),
                alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = reactApplicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = System.currentTimeMillis() + snoozeMinutes * 60_000L
            
            // Create show intent for alarm clock info
            val showIntent = reactApplicationContext.packageManager.getLaunchIntentForPackage(reactApplicationContext.packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("alarm_id", id)
            }
            
            val showPendingIntent = if (showIntent != null) {
                PendingIntent.getActivity(
                    reactApplicationContext,
                    id.hashCode() + 999,
                    showIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                pendingIntent
            }
            
            val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerAt, showPendingIntent)
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
            Log.d(TAG, "Snoozed alarm for $snoozeMinutes minutes")
            promise.resolve(null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to snooze alarm: ${e.message}")
            promise.reject("SNOOZE_ERROR", e)
        }
    }

    @ReactMethod
    fun addListener(eventName: String) {
        // Required for NativeEventEmitter
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        // Required for NativeEventEmitter
    }

    private fun saveAlarm(id: String, datetimeISO: String, title: String, body: String, snoozeEnabled: Boolean = true, snoozeInterval: Int = 5, repeatFrequency: Int = -1) {
        val obj = JSONObject()
            .put("id", id)
            .put("datetimeISO", datetimeISO)
            .put("title", title)
            .put("body", body)
            .put("snoozeEnabled", snoozeEnabled)
            .put("snoozeInterval", snoozeInterval)
            .put("repeatFrequency", repeatFrequency)
        val prefs = reactApplicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(id, obj.toString()).apply()
    }

    private fun removeAlarm(id: String) {
        val prefs = reactApplicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().remove(id).apply()
    }

    private fun getAlarm(id: String): JSONObject? {
        val prefs = reactApplicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(id, null) ?: return null
        return try {
            JSONObject(jsonStr)
        } catch (_: Exception) {
            null
        }
    }
}