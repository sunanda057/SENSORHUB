package com.sensorhub.app

import android.app.Application
import com.google.firebase.database.FirebaseDatabase

class SensorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Keeps data written while offline and syncs it automatically when the network returns.
        try {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true)
        } catch (_: Exception) {
        }
    }
}
