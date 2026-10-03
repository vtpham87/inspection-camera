package com.ttdk1507d.inspectioncamera.firebase

import android.os.Build
import android.util.Base64
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.ttdk1507d.inspectioncamera.model.Vehicle
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

object FirebaseManager {
    private const val TAG = "FirebaseManager"
    private const val RTDB_URL = "https://ttdk-1507d-default-rtdb.asia-southeast1.firebasedatabase.app"

    val database: FirebaseDatabase by lazy {
        FirebaseDatabase.getInstance(RTDB_URL)
    }

    /**
     * Lắng nghe trạng thái kết nối với máy chủ Firebase Realtime Database
     */
    fun observeConnection(): Flow<Boolean> = callbackFlow {
        val connectedRef = database.getReference(".info/connected")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                trySend(connected)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Connection listener cancelled: ${error.message}")
            }
        }
        connectedRef.addValueEventListener(listener)
        awaitClose { connectedRef.removeEventListener(listener) }
    }

    /**
     * Lắng nghe danh sách xe kiểm định hôm nay từ Firebase RTDB node /vehicles_today
     */
    fun observeVehicles(): Flow<List<Vehicle>> = callbackFlow {
        val vehiclesRef = database.getReference("vehicles_today")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<Vehicle>()
                for (child in snapshot.children) {
                    try {
                        val v = child.getValue(Vehicle::class.java)
                        if (v != null) {
                            if (v.plateClean.isEmpty()) {
                                v.plateClean = child.key ?: ""
                            }
                            if (v.plate.isEmpty()) {
                                v.plate = v.plateClean
                            }
                            list.add(v)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing vehicle ${child.key}: ${e.message}")
                    }
                }
                trySend(list)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Vehicles listener cancelled: ${error.message}")
            }
        }
        vehiclesRef.addValueEventListener(listener)
        awaitClose { vehiclesRef.removeEventListener(listener) }
    }

    /**
     * Tải ảnh chụp lên Firebase RTDB node /photo_inbox (Coroutine Suspend)
     */
    suspend fun uploadPhotoToInbox(
        plate: String,
        plateColor: String?,
        photoType: String,
        seq: Int,
        lanKd: Int,
        photoFile: File
    ): Boolean = suspendCancellableCoroutine { continuation ->
        if (!photoFile.exists()) {
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }
        try {
            val bytes = photoFile.readBytes()
            val base64Str = Base64.encodeToString(bytes, Base64.NO_WRAP)

            val inboxRef = database.getReference("photo_inbox")
            val newPhotoRef = inboxRef.push()
            val photoId = newPhotoRef.key ?: System.currentTimeMillis().toString()

            val cleanPlate = plate.replace(Regex("[^a-zA-Z0-9]"), "").uppercase()

            val payload = hashMapOf(
                "id" to photoId,
                "plate" to plate,
                "plate_clean" to cleanPlate,
                "plate_color" to (plateColor ?: "T"),
                "photo_type" to photoType,
                "seq" to seq,
                "lan_kd" to lanKd,
                "image_base64" to base64Str,
                "file_size" to bytes.size,
                "created_at" to System.currentTimeMillis(),
                "device_model" to Build.MODEL,
                "status" to "pending"
            )

            newPhotoRef.setValue(payload)
                .addOnSuccessListener {
                    Log.d(TAG, "Photo pushed to Firebase inbox: $photoId for $plate ($photoType)")
                    if (continuation.isActive) continuation.resume(true)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to push photo to Firebase inbox: ${e.message}")
                    if (continuation.isActive) continuation.resume(false)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during uploadPhotoToInbox: ${e.message}", e)
            if (continuation.isActive) continuation.resume(false)
        }
    }
}
