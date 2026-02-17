package com.rnalarmmodule

import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.*
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import android.util.Log

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
        const val ACTION_STOP = "com.rnalarmmodule.ACTION_STOP_ALARM"
        const val ACTION_SNOOZE = "com.rnalarmmodule.ACTION_SNOOZE_ALARM"
        private const val CHANNEL_ID = "rn_alarm_module_channel"
        private const val RAW_RES = "alarm_default"
        private const val SNOOZE_MINUTES = 5
        private const val AUTO_STOP_SECONDS = 60

        @Volatile
        private var player: MediaPlayer? = null

        @Volatile
        private var audioManager: AudioManager? = null

        @Volatile
        private var originalVolume: Int = 0

        @Volatile
        var activeAlarmId: String? = null

        @Volatile
        private var wakeLock: PowerManager.WakeLock? = null
    }

    override fun onReceive(context: Context, intent: Intent?) {
        intent ?: return
        val action = intent.action
        val id = intent.getStringExtra("id") ?: return

        Log.d(TAG, "onReceive: action=$action id=$id")

        when (action) {
            ACTION_STOP -> {
                stopAlarm(context, id)
                return
            }

            ACTION_SNOOZE -> {
                stopAlarm(context, id)
                scheduleSnooze(context, intent)
                return
            }
        }

        val title = intent.getStringExtra("title") ?: "Alarm"
        val body = intent.getStringExtra("body") ?: ""
        val snoozeEnabled = intent.getBooleanExtra("snoozeEnabled", true)
        val snoozeInterval = intent.getIntExtra("snoozeInterval", SNOOZE_MINUTES)
        val repeatFrequency = intent.getIntExtra("repeatFrequency", -1)
        activeAlarmId = id

        emitActiveAlarmId(activeAlarmId)

        acquireWakeLock(context)
        setupAudio(context)
        playAlarm(context, id)
        showNotificationWithActions(context, id, title, body, snoozeEnabled, snoozeInterval)
        
        // Auto-reschedule repeating alarms
        if (repeatFrequency >= 0) {
            rescheduleRepeatingAlarm(context, id, title, body, snoozeEnabled, snoozeInterval, repeatFrequency)
        }
    }

    private fun acquireWakeLock(context: Context) {
        if (wakeLock?.isHeld == true) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rnalarmmodule:AlarmWakeLock").apply {
            setReferenceCounted(false)
            // Acquire for a bounded time (65s) – alarm auto-stops at 60s.
            acquire((AUTO_STOP_SECONDS + 5) * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun setupAudio(context: Context) {
        if (audioManager == null) {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }
        audioManager?.let { am ->
            originalVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(
                AudioManager.STREAM_ALARM,
                maxVolume,
                AudioManager.FLAG_SHOW_UI or AudioManager.FLAG_PLAY_SOUND
            )

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val focusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener {}
                    .build()
            } else null

            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                am.requestAudioFocus(focusRequest!!)
            } else {
                am.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
        }
    }

    private fun playAlarm(context: Context, id: String) {
        player?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }

        player = MediaPlayer().apply {
            try {
                // Try to use custom alarm sound from host app's res/raw folder
                val resId = context.resources.getIdentifier(RAW_RES, "raw", context.packageName)
                if (resId != 0) {
                    setDataSource(
                        context,
                        android.net.Uri.parse("android.resource://${context.packageName}/raw/$RAW_RES")
                    )
                } else {
                    // Fallback to default alarm sound
                    val alarmUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                        ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                    setDataSource(context, alarmUri)
                }
                
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                @Suppress("DEPRECATION")
                setAudioStreamType(AudioManager.STREAM_ALARM)
                isLooping = true
                prepare()
                start()
                Log.d(TAG, "Alarm sound started for id=$id")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play alarm sound: ${e.message}")
                e.printStackTrace()
            }
        }

        // Auto-stop after configured time
        Thread {
            try {
                Thread.sleep(AUTO_STOP_SECONDS * 1000L)
            } catch (_: InterruptedException) {
            } finally {
                if (activeAlarmId == id) {
                    Log.d(TAG, "Auto-stopping alarm id=$id after ${AUTO_STOP_SECONDS}s")
                    stopAlarm(context, id)
                }
            }
        }.start()
    }

    private fun showNotificationWithActions(context: Context, id: String, title: String, body: String, snoozeEnabled: Boolean = true, snoozeInterval: Int = SNOOZE_MINUTES) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Alarms", NotificationManager.IMPORTANCE_HIGH
            ).apply { 
                setSound(null, null)
                description = "Alarm notifications"
            }
            nm.createNotificationChannel(channel)
        }

        val stopIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_STOP
            putExtra("id", id)
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context, id.hashCode() + 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozePendingIntent = if (snoozeEnabled) {
            val snoozeIntent = Intent(context, AlarmReceiver::class.java).apply {
                action = ACTION_SNOOZE
                putExtra("id", id)
                putExtra("title", title)
                putExtra("body", "$body (Snoozed)")
                putExtra("snoozeMinutes", snoozeInterval)
                putExtra("snoozeEnabled", snoozeEnabled)
                putExtra("snoozeInterval", snoozeInterval)
            }
            PendingIntent.getBroadcast(
                context, id.hashCode() + 2, snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        // Try to get the host app's launcher activity
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("alarm_id", id)
        }
        
        val fullScreenPendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                context, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setSound(null)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // Only add snooze action if snooze is enabled
        if (snoozeEnabled && snoozePendingIntent != null) {
            builder.addAction(android.R.drawable.ic_menu_recent_history, "Snooze", snoozePendingIntent)
        }

        if (fullScreenPendingIntent != null) {
            builder.setFullScreenIntent(fullScreenPendingIntent, true)
            builder.setContentIntent(fullScreenPendingIntent)
        }

        val notification = builder.build().apply { 
            flags = flags or Notification.FLAG_NO_CLEAR 
        }

        nm.notify(id.hashCode(), notification)
        Log.d(TAG, "Notification shown for alarm id=$id")
    }

    private fun stopAlarm(context: Context, id: String?) {
        Log.d(TAG, "Stopping alarm id=$id")
        
        player?.apply {
            try {
                if (isPlaying) stop()
            } catch (_: Exception) {
            }
            release()
        }
        player = null

        audioManager?.let { am ->
            try {
                am.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0)
            } catch (_: Exception) {
            }
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    am.abandonAudioFocusRequest(
                        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).build()
                    )
                } catch (_: Exception) {
                }
            } else {
                am.abandonAudioFocus(null)
            }
        }

        releaseWakeLock()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        id?.let { nm.cancel(it.hashCode()) }

        if (activeAlarmId == id) {
            activeAlarmId = null
            emitActiveAlarmId(null)
        }
    }

    private fun scheduleSnooze(context: Context, originalIntent: Intent) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val id = originalIntent.getStringExtra("id") ?: return
        val title = originalIntent.getStringExtra("title") ?: "Alarm"
        val body = originalIntent.getStringExtra("body") ?: ""
        val minutes = originalIntent.getIntExtra("snoozeMinutes", SNOOZE_MINUTES)
        val repeatFrequency = originalIntent.getIntExtra("repeatFrequency", -1)
        val snoozeEnabled = originalIntent.getBooleanExtra("snoozeEnabled", true)
        val snoozeInterval = originalIntent.getIntExtra("snoozeInterval", SNOOZE_MINUTES)

        val snoozeIntent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("id", id)
            putExtra("title", title)
            putExtra("body", body)
            putExtra("repeatFrequency", repeatFrequency)
            putExtra("snoozeEnabled", snoozeEnabled)
            putExtra("snoozeInterval", snoozeInterval)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context, id.hashCode(), snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = System.currentTimeMillis() + minutes * 60L * 1000L

        val showPendingIntent = AlarmModule.createShowIntent(context, id, pendingIntent)
        val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerTime, showPendingIntent)
        alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)

        Toast.makeText(context, "Snoozed for $minutes minutes", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Alarm $id snoozed for $minutes minutes")
    }

    /**
     * Reschedule a repeating alarm for its next occurrence.
     * This method is called automatically after a repeating alarm triggers to schedule
     * the next occurrence at the exact interval (hourly, daily, or weekly).
     * 
     * @param context The Android context
     * @param id The unique alarm identifier
     * @param title The alarm notification title
     * @param body The alarm notification body
     * @param snoozeEnabled Whether snoozing is enabled for this alarm
     * @param snoozeInterval The snooze interval in minutes
     * @param repeatFrequency The repeat frequency (0=hourly, 1=daily, 2=weekly)
     */
    private fun rescheduleRepeatingAlarm(context: Context, id: String, title: String, body: String, snoozeEnabled: Boolean, snoozeInterval: Int, repeatFrequency: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intervalMillis = AlarmModule.getRepeatIntervalMillis(repeatFrequency)
        
        // Calculate next trigger time
        // Note: intervalMillis is bounded (max 7 days), so overflow is not a practical concern
        val currentTime = System.currentTimeMillis()
        val nextTriggerTime = currentTime + intervalMillis

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

        val showPendingIntent = AlarmModule.createShowIntent(context, id, pendingIntent)
        val alarmClockInfo = AlarmManager.AlarmClockInfo(nextTriggerTime, showPendingIntent)
        alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)

        Log.d(TAG, "Rescheduled repeating alarm id=$id for next occurrence in ${intervalMillis / 1000}s")
    }

    private fun emitActiveAlarmId(id: String?) {
        // Use AlarmModule's static context reference instead of MainApplication
        val reactContext = AlarmModule.getReactContext()

        if (reactContext != null && reactContext.hasActiveCatalystInstance()) {
            try {
                reactContext
                    .getJSModule(RCTDeviceEventEmitter::class.java)
                    .emit("activeAlarmId", id)
                Log.d(TAG, "Emitted activeAlarmId event: $id")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to emit activeAlarmId event: ${e.message}")
            }
        } else {
            Log.w(TAG, "Cannot emit event: ReactContext not available")
        }
    }
}