package com.cabfusion.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.cabfusion.app.data.ServiceLocator
import org.osmdroid.config.Configuration

class CabFusionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // OpenStreetMap tile servers require an identifying user agent.
        Configuration.getInstance().apply {
            userAgentValue = "CabFusion/${BuildConfig.VERSION_NAME} (student project, Chennai Institute of Technology)"
            osmdroidBasePath = cacheDir
            osmdroidTileCache = cacheDir.resolve("tiles")
        }
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_RIDES, getString(R.string.channel_rides), NotificationManager.IMPORTANCE_DEFAULT))
        }
        ServiceLocator.init(this)
    }

    companion object { const val CHANNEL_RIDES = "rides" }
}
