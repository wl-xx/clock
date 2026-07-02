package com.example.pinkschedule.reminder

import android.content.Context
import android.os.PowerManager

object WakeLockManager {
    private const val TAG = "PinkSchedule:AlarmWakeLock"
    private const val STARTUP_TAG = "PinkSchedule:AlarmStartupWakeLock"
    private const val ALARM_WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L
    private const val STARTUP_WAKE_LOCK_TIMEOUT_MS = 30_000L

    @Volatile
    private var startupWakeLock: PowerManager.WakeLock? = null

    // 仅保留 PARTIAL_WAKE_LOCK：铃响期间保持 CPU 唤醒，提醒展示交给通知/横幅。
    fun acquire(context: Context, timeoutMs: Long = ALARM_WAKE_LOCK_TIMEOUT_MS): PowerManager.WakeLock? {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            TAG
        )
        wakeLock.setReferenceCounted(false)
        wakeLock.acquire(timeoutMs)
        return wakeLock
    }

    fun acquireStartup(context: Context, timeoutMs: Long = STARTUP_WAKE_LOCK_TIMEOUT_MS): PowerManager.WakeLock? {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        synchronized(this) {
            val existing = startupWakeLock
            if (existing?.isHeld == true) {
                return existing
            }
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                STARTUP_TAG
            )
            wakeLock.setReferenceCounted(false)
            wakeLock.acquire(timeoutMs)
            startupWakeLock = wakeLock
            return wakeLock
        }
    }

    fun releaseStartup() {
        synchronized(this) {
            release(startupWakeLock)
            startupWakeLock = null
        }
    }

    fun release(wakeLock: PowerManager.WakeLock?) {
        if (wakeLock?.isHeld == true) {
            runCatching {
                wakeLock.release()
            }
        }
    }
}
