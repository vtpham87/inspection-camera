package com.ttdk1507d.inspectioncamera

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase

class InspectionCameraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            FirebaseApp.initializeApp(this)
            FirebaseDatabase.getInstance("https://ttdk-1507d-default-rtdb.asia-southeast1.firebasedatabase.app").setPersistenceEnabled(true)
            Log.d("InspectionCameraApp", "Firebase initialized with persistence enabled")
        } catch (e: Exception) {
            Log.e("InspectionCameraApp", "Error configuring Firebase persistence", e)
        }
    }
}
