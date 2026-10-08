package com.ttdk1507d.inspectioncamera

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase
import com.ttdk1507d.inspectioncamera.firebase.FirebaseManager

class InspectionCameraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            FirebaseApp.initializeApp(this)
            for (url in FirebaseManager.ALL_URLS) {
                try {
                    val db = FirebaseDatabase.getInstance(url)
                    db.setPersistenceEnabled(true)
                    db.setPersistenceCacheSizeBytes(10 * 1024 * 1024L) // Giới hạn tối đa 10MB LRU
                } catch (e: Exception) {
                    Log.e("InspectionCameraApp", "Error configuring persistence for $url", e)
                }
            }
            Log.d("InspectionCameraApp", "Firebase multi-cluster initialized with persistence")
        } catch (e: Exception) {
            Log.e("InspectionCameraApp", "Error configuring Firebase", e)
        }
    }
}
