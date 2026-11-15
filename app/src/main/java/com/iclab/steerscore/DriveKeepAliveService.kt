package com.iclab.steerscore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Keeps the app running while the driver looks at the navigation. In live mode the app samples the car 10 times a
 * second and coaches by voice from the same process; when the navigation is in front, Android froze the process within
 * about 25 seconds (emulator: dumpsys activity processes, isFrozen=true), so scoring and voice coaching stopped exactly
 * when they are meant to run. A foreground service keeps the process out of the freezer; it does no work of its own.
 */
class DriveKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "주행 점수", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("SteerScore")
            .setContentText("주행 점수와 음성 코칭이 켜져 있어요")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, notification)
            Log.i(TAG, "keep-alive service started")
        }.onFailure { Log.w(TAG, "keep-alive service: ${it.message}") }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "SteerScore"
        private const val CHANNEL = "drive"
        private const val ID = 7

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, DriveKeepAliveService::class.java)) }
                .onFailure { Log.w(TAG, "start keep-alive: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DriveKeepAliveService::class.java)) }
        }
    }
}
