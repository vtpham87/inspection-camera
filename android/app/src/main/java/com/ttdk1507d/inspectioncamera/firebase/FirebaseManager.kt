package com.ttdk1507d.inspectioncamera.firebase

import android.os.Build
import android.util.Base64
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ttdk1507d.inspectioncamera.model.Vehicle
import com.ttdk1507d.inspectioncamera.util.PlateUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlin.coroutines.resume

data class FirebaseNode(
    val id: String,
    val name: String,
    val scheduleDesc: String,
    val url: String,
    val activeDays: List<Int>
)

data class NodeTestResult(
    val node: FirebaseNode,
    val isOk: Boolean,
    val latencyMs: Long,
    val error: String? = null
)

object FirebaseManager {
    private const val TAG = "FirebaseManager"

    val NODE_1 = FirebaseNode(
        id = "node1",
        name = "Cụm 1",
        scheduleDesc = "Thứ 2 & Thứ 5",
        url = "https://ttdk-1507d-default-rtdb.asia-southeast1.firebasedatabase.app",
        activeDays = listOf(Calendar.MONDAY, Calendar.THURSDAY)
    )

    val NODE_2 = FirebaseNode(
        id = "node2",
        name = "Cụm 2",
        scheduleDesc = "Thứ 3 & Thứ 6",
        url = "https://ttdk-1507d-p2-default-rtdb.asia-southeast1.firebasedatabase.app",
        activeDays = listOf(Calendar.TUESDAY, Calendar.FRIDAY)
    )

    val NODE_3 = FirebaseNode(
        id = "node3",
        name = "Cụm 3",
        scheduleDesc = "Thứ 4 & Thứ 7",
        url = "https://ttdk-1507d-p3-default-rtdb.asia-southeast1.firebasedatabase.app",
        activeDays = listOf(Calendar.WEDNESDAY, Calendar.SATURDAY)
    )

    val NODE_BACKUP = FirebaseNode(
        id = "backup",
        name = "Cụm Dự Phòng",
        scheduleDesc = "Tự động kích hoạt khi có sự cố (Failover)",
        url = "https://ttdk-1507d-bk-default-rtdb.asia-southeast1.firebasedatabase.app",
        activeDays = emptyList()
    )

    val ALL_NODES = listOf(NODE_1, NODE_2, NODE_3, NODE_BACKUP)
    val ALL_URLS = ALL_NODES.map { it.url }

    fun getScheduledNode(): FirebaseNode {
        val dayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return ALL_NODES.firstOrNull { it.activeDays.contains(dayOfWeek) } ?: NODE_1
    }

    fun getDatabase(node: FirebaseNode = getScheduledNode()): FirebaseDatabase {
        return FirebaseDatabase.getInstance(node.url)
    }

    /**
     * Ngắt và tái kết nối lại toàn bộ socket Firebase để làm mới cache và xóa kết nối stale
     */
    fun reconnect() {
        try {
            for (url in ALL_URLS) {
                FirebaseDatabase.getInstance(url).goOffline()
                FirebaseDatabase.getInstance(url).goOnline()
            }
            Log.d(TAG, "Đã tái kết nối toàn bộ cụm Firebase")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi reconnect Firebase: ${e.message}")
        }
    }

    /**
     * Lắng nghe danh sách xe kiểm định hôm nay từ Firebase RTDB node /vehicles_today
     */
    fun observeVehicles(node: FirebaseNode = getScheduledNode()): Flow<List<Vehicle>> = callbackFlow {
        val database = getDatabase(node)
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
     * Tải ảnh chụp lên Firebase RTDB node /photo_inbox với cơ chế failover:
     * 1. Thử gửi lên cụm chính theo lịch ngày
     * 2. Nếu thất bại, tự động chuyển sang Cụm Dự Phòng
     */
    suspend fun uploadPhotoToInbox(
        plate: String,
        plateColor: String?,
        photoType: String,
        seq: Int,
        lanKd: Int,
        photoFile: File
    ): Boolean {
        if (!photoFile.exists()) return false

        val primaryNode = getScheduledNode()
        Log.d(TAG, "Uploading photo to primary node ${primaryNode.name}...")

        // Thử node chính (timeout 12s)
        val primarySuccess = withTimeoutOrNull(12_000L) {
            pushPhotoToNode(primaryNode, plate, plateColor, photoType, seq, lanKd, photoFile)
        } ?: false

        if (primarySuccess) {
            Log.d(TAG, "Uploaded successfully to primary node ${primaryNode.name}")
            return true
        }

        // Chuyển sang node dự phòng Failover
        Log.w(TAG, "Primary node ${primaryNode.name} upload failed, failing over to ${NODE_BACKUP.name}...")
        val backupSuccess = withTimeoutOrNull(12_000L) {
            pushPhotoToNode(NODE_BACKUP, plate, plateColor, photoType, seq, lanKd, photoFile)
        } ?: false

        if (backupSuccess) {
            Log.i(TAG, "Uploaded successfully to backup node ${NODE_BACKUP.name}")
            return true
        }

        Log.e(TAG, "Upload failed on both primary and backup nodes for plate $plate ($photoType)")
        return false
    }

    private suspend fun pushPhotoToNode(
        node: FirebaseNode,
        plate: String,
        plateColor: String?,
        photoType: String,
        seq: Int,
        lanKd: Int,
        photoFile: File
    ): Boolean = suspendCancellableCoroutine { continuation ->
        try {
            if (!photoFile.exists()) {
                Log.e(TAG, "File ảnh không tồn tại: ${photoFile.absolutePath}")
                if (continuation.isActive) continuation.resume(false)
                return@suspendCancellableCoroutine
            }
            if (photoFile.length() > 7 * 1024 * 1024) {
                Log.e(TAG, "File ảnh quá lớn (${photoFile.length()} bytes > 7MB) để tải lên Firebase RTDB")
                if (continuation.isActive) continuation.resume(false)
                return@suspendCancellableCoroutine
            }
            val bytes = photoFile.readBytes()
            val base64Str = Base64.encodeToString(bytes, Base64.NO_WRAP)

            val db = getDatabase(node)
            val inboxRef = db.getReference("photo_inbox")
            val newPhotoRef = inboxRef.push()
            val photoId = newPhotoRef.key ?: System.currentTimeMillis().toString()

            val cleanPlate = plate.replace(Regex("[^a-zA-Z0-9]"), "").uppercase()
            val isOld = PlateUtil.isOldPlate(cleanPlate)
            val finalColor = if (isOld) "" else (plateColor ?: "T")

            val payload = hashMapOf(
                "id" to photoId,
                "plate" to plate,
                "plate_clean" to cleanPlate,
                "plate_color" to finalColor,
                "photo_type" to photoType,
                "seq" to seq,
                "lan_kd" to lanKd,
                "image_base64" to base64Str,
                "file_size" to bytes.size,
                "created_at" to System.currentTimeMillis(),
                "device_model" to Build.MODEL,
                "status" to "pending",
                "node_id" to node.id
            )

            newPhotoRef.setValue(payload)
                .addOnSuccessListener {
                    Log.d(TAG, "[${node.name}] Photo pushed: $photoId for $plate ($photoType)")
                    if (continuation.isActive) continuation.resume(true)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "[${node.name}] Failed to push photo: ${e.message}")
                    if (continuation.isActive) continuation.resume(false)
                }
        } catch (e: Exception) {
            Log.e(TAG, "[${node.name}] Exception pushing photo: ${e.message}", e)
            if (continuation.isActive) continuation.resume(false)
        }
    }

    /**
     * Kiểm tra trạng thái và tốc độ phản hồi (latency) của cả 4 cụm máy chủ Firebase
     */
    suspend fun testAllNodes(): List<NodeTestResult> = withContext(Dispatchers.IO) {
        ALL_NODES.map { node ->
            try {
                val start = System.currentTimeMillis()
                val url = URL("${node.url}/.json?shallow=true")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                val latency = System.currentTimeMillis() - start
                conn.disconnect()
                if (code in 200..299) {
                    NodeTestResult(node, true, latency)
                } else {
                    NodeTestResult(node, false, latency, "HTTP $code")
                }
            } catch (e: Exception) {
                NodeTestResult(node, false, 0, e.message ?: "Không phản hồi")
            }
        }
    }

    /**
     * Lắng nghe cấu hình thời gian thực từ máy tính trạm qua Firebase (/config)
     */
    fun observeConfig(onConfigChange: (Map<String, Any>) -> Unit): ValueEventListener? {
        val node = getScheduledNode()
        return try {
            val db = FirebaseDatabase.getInstance(node.url)
            val ref = db.getReference("config")
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    @Suppress("UNCHECKED_CAST")
                    val value = snapshot.value as? Map<String, Any>
                    if (value != null) {
                        Log.d(TAG, "Đã nhận cấu hình từ Firebase [${node.name}]: $value")
                        onConfigChange(value)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.w(TAG, "Lỗi lắng nghe config Firebase: ${error.message}")
                }
            }
            ref.addValueEventListener(listener)
            listener
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi kết nối lắng nghe config: ${e.message}", e)
            null
        }
    }

    /**
     * Hủy đăng ký lắng nghe cấu hình Firebase
     */
    fun removeConfigListener(listener: ValueEventListener?) {
        if (listener == null) return
        try {
            val node = getScheduledNode()
            val db = FirebaseDatabase.getInstance(node.url)
            db.getReference("config").removeEventListener(listener)
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi khi gỡ listener config: ${e.message}")
        }
    }

    /**
     * Lấy cấu hình một lần từ Firebase (/config)
     */
    suspend fun fetchConfigOnce(): Map<String, Any>? = withContext(Dispatchers.IO) {
        val node = getScheduledNode()
        try {
            val url = URL("${node.url}/config.json")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"
            if (conn.responseCode in 200..299) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                if (text.isNotEmpty() && text != "null") {
                    val mapType = object : TypeToken<Map<String, Any>>() {}.type
                    Gson().fromJson<Map<String, Any>>(text, mapType)
                } else null
            } else {
                conn.disconnect()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi fetchConfigOnce: ${e.message}")
            null
        }
    }

    /**
     * Gửi yêu cầu cập nhật cấu hình từ điện thoại lên máy tính trạm (/config_update và /config)
     */
    suspend fun sendConfigUpdate(updates: Map<String, Any>): Boolean = withContext(Dispatchers.IO) {
        try {
            val jsonPayload = Gson().toJson(updates)
            ALL_NODES.forEach { targetNode ->
                try {
                    val url = URL("${targetNode.url}/config_update.json")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    conn.requestMethod = "PUT"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    conn.outputStream.use { os ->
                        os.write(jsonPayload.toByteArray(Charsets.UTF_8))
                    }
                    val code = conn.responseCode
                    conn.disconnect()
                    Log.d(TAG, "Sent config_update to ${targetNode.name}: code $code")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send config_update to ${targetNode.name}: ${e.message}")
                }

                // Cập nhật trực tiếp /config.json để app không bị ghi đè ngược cấu hình cũ
                try {
                    val urlConfig = URL("${targetNode.url}/config.json")
                    val connConfig = urlConfig.openConnection() as HttpURLConnection
                    connConfig.connectTimeout = 4000
                    connConfig.readTimeout = 4000
                    connConfig.requestMethod = "PATCH"
                    connConfig.doOutput = true
                    connConfig.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    connConfig.outputStream.use { os ->
                        os.write(jsonPayload.toByteArray(Charsets.UTF_8))
                    }
                    val code = connConfig.responseCode
                    connConfig.disconnect()
                    Log.d(TAG, "Sent config patch to ${targetNode.name}: code $code")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to patch config to ${targetNode.name}: ${e.message}")
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi sendConfigUpdate: ${e.message}", e)
            false
        }
    }
}
