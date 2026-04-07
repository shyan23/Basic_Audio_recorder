package com.soundtag.app

import android.app.Application
import com.soundtag.app.notifications.NotificationHelper

class SoundTagApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
    }
}
