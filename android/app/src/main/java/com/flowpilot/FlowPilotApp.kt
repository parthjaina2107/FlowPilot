package com.flowpilot

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class FlowPilotApp : Application() {

    companion object {
        const val CHANNEL_RECORDING = "flowpilot_recording"
        const val CHANNEL_REPLAY = "flowpilot_replay"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val recordingChannel = NotificationChannel(
                CHANNEL_RECORDING,
                "Flow Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when FlowPilot is recording your actions"
            }

            val replayChannel = NotificationChannel(
                CHANNEL_REPLAY,
                "Flow Replay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress when FlowPilot is replaying a flow"
            }

            manager.createNotificationChannel(recordingChannel)
            manager.createNotificationChannel(replayChannel)
        }
    }
}
