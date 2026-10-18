package com.ttdk1507d.inspectioncamera.firebase

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ttdk1507d.inspectioncamera.BuildConfig
import com.ttdk1507d.inspectioncamera.model.Vehicle
import com.ttdk1507d.inspectioncamera.util.PlateUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
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
    private val listenerMap = mutableMapOf<ValueEventListener, DatabaseReference>()
    private const val TAG = "FirebaseManager"
    private const val MAX_RAW_BYTES = 500 * 1024
    private const val REST_TIMEOUT_MS = 4000

    // Bản dev có thể trỏ sang cụm Firebase riêng (BuildConfig.FIREBASE_URLS: danh sách URL cách nhau dấu phẩy,
    // thứ tự: cụm 1, 2, 3, dự phòng). Rỗng = dùng URL mặc định của bản chính thức.
    private val urlOverride: List<String> =
        BuildConfig.FIREBASE_URLS.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    private fun urlOf(index: Int, default: String): String = urlOverride.getOrNull(index) ?: default

    val NODE_1 = FirebaseNode(
        id = "node1",
        name = "Cụm 1",
        scheduleDesc = "Thứ 2 & Thứ 5",
        url = urlOf(0, "https://ttdk-1507d-default-rtdb.asia-southeast1.firebasedatabase.app"),
        activeDays = listOf(Calendar.MONDAY, Calendar.THURSDAY)
    )

    val NODE_2 = FirebaseNode(
        id = "node2",
        name = "Cụm 2",
        scheduleDesc = "Thứ 3 & Thứ 6",
        url = urlOf(1, "https://ttdk-1507d-p2-default-rtdb.asia-southeast1.firebasedatabase.app"),
        activeDays = listOf(Calendar.TUESDAY, Calendar.FRIDAY)
    )

    val NODE_3 = FirebaseNode(
        id = "node3",
        name = "Cụm 3",
        scheduleDesc = "Thứ 4 & Thứ 7",
        url = urlOf(2, "https://ttdk-1507d-p3-default-rtdb.asia-southeast1.firebasedatabase.app"),
        activeDays = listOf(Calendar.WEDNESDAY, Calendar.SATURDAY)
    )

    val NODE_BACKUP = FirebaseNode(
        id = "backup",
        name = "Cụm Dự Phòng",
        scheduleDesc = "Tự động kích hoạt khi có sự cố (Failover)",
        url = urlOf(3, "https://ttdk-1507d-bk-default-rtdb.asia-southeast1.firebasedatabase.app"),
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
        val prepared = preparePhoto(photoFile) ?: return false

        // Cụm chính theo lịch ngày, thất bại (hoặc quá 12s) thì failover sang cụm dự phòng
        for (node in listOf(getScheduledNode(), NODE_BACKUP).distinct()) {
            Log.d(TAG, "Uploading photo to node ${node.name}...")
            val ok = withTimeoutOrNull(12_000L) {
                pushPhotoToNode(node, plate, plateColor, photoType, seq, lanKd, prepared)
            } ?: false
            if (ok) {
                Log.i(TAG, "Uploaded successfully to ${node.name}")
                return true
            }
            Log.w(TAG, "Upload to ${node.name} failed")
        }
        Log.e(TAG, "Upload failed on both primary and backup nodes for plate $plate ($photoType)")
        return false
    }

    /**
     * Xoá ảnh khỏi hàng đợi photo_inbox trên mọi cụm (gọi khi KĐV xoá ảnh). Nhờ persistence của SDK, lệnh ghi
     * trước đó còn nằm trong hàng đợi sẽ bị lệnh xoá này huỷ, tránh ảnh đã xoá "sống lại" trên máy trạm.
     */
    fun cancelInboxPhoto(plate: String, photoType: String, seq: Int, lanKd: Int) {
        val cleanPlate = plate.replace(Regex("[^a-zA-Z0-9]"), "").uppercase()
        val photoId = "${cleanPlate}_L${lanKd}_${photoType}_${seq}"
        for (node in ALL_NODES) {
            try {
                getDatabase(node).getReference("photo_inbox").child(photoId).removeValue()
            } catch (e: Exception) {
                Log.w(TAG, "[${node.name}] Không huỷ được ảnh $photoId trong inbox: ${e.message}")
            }
        }
    }

    /** Ảnh đã nén/mã hoá Base64, dùng chung cho cụm chính và cụm dự phòng (chỉ xử lý 1 lần). */
    private class PreparedPhoto(
        val base64: String,
        val size: Int,
        val degraded: Boolean,
        val originalSize: Long
    )

    /**
     * Đọc + nén (nếu > 500KB) + Base64 trên luồng IO. Làm 1 lần cho cả hai cụm và nằm ngoài timeout 12s của từng cụm,
     * để máy chậm không bị tính thời gian xử lý ảnh vào thời gian chờ mạng.
     */
    private suspend fun preparePhoto(photoFile: File): PreparedPhoto? = withContext(Dispatchers.IO) {
        try {
            if (!photoFile.exists()) {
                Log.e(TAG, "File ảnh không tồn tại: ${photoFile.absolutePath}")
                return@withContext null
            }
            val fileSize = photoFile.length()
            var bytes: ByteArray? = null
            var degraded = false
            if (fileSize > MAX_RAW_BYTES) { // tránh vượt giới hạn Base64 của Firebase
                Log.w(TAG, "File ảnh > 500KB ($fileSize bytes). Nén lại (inSampleSize) trước khi upload dự phòng...")
                bytes = recompressForFallback(photoFile)
                degraded = bytes != null
            }
            val finalBytes = bytes ?: photoFile.readBytes()
            PreparedPhoto(Base64.encodeToString(finalBytes, Base64.NO_WRAP), finalBytes.size, degraded, fileSize)
        } catch (e: Exception) {
            Log.e(TAG, "Không chuẩn bị được ảnh ${photoFile.name}: ${e.message}", e)
            null
        }
    }

    /** Giảm về tối đa 1280px, thử chất lượng 70 → 55 → 40 cho tới khi ≤ 500KB. Trả null nếu không decode được. */
    private fun recompressForFallback(photoFile: File): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photoFile.absolutePath, bounds)
        var sampleSize = 1
        val maxDim = 1280 // kích thước tối đa cho ảnh gửi dự phòng để Base64 nhỏ
        while ((bounds.outWidth / sampleSize) > maxDim || (bounds.outHeight / sampleSize) > maxDim) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bmp = try {
            BitmapFactory.decodeFile(photoFile.absolutePath, options)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Thiếu bộ nhớ khi nén ảnh dự phòng: ${e.message}")
            null
        } ?: return null
        try {
            var result: ByteArray? = null
            for (quality in intArrayOf(70, 55, 40)) {
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                result = baos.toByteArray()
                if (result.size <= MAX_RAW_BYTES) break
            }
            return result
        } finally {
            bmp.recycle()
        }
    }

    private suspend fun pushPhotoToNode(
        node: FirebaseNode,
        plate: String,
        plateColor: String?,
        photoType: String,
        seq: Int,
        lanKd: Int,
        prepared: PreparedPhoto
    ): Boolean = suspendCancellableCoroutine { continuation ->
        try {
            val base64Str = prepared.base64
            val degraded = prepared.degraded
            val fileSize = prepared.originalSize

            val cleanPlate = plate.replace(Regex("[^a-zA-Z0-9]"), "").uppercase()

            // Khoá xác định (không dùng push()) để gửi lại cùng một ảnh chỉ ghi đè một nút,
            // tránh PC nhận trùng khi các lần thử bị Firebase xếp hàng lúc mất mạng.
            val photoId = "${cleanPlate}_L${lanKd}_${photoType}_${seq}"
            val db = getDatabase(node)
            val newPhotoRef = db.getReference("photo_inbox").child(photoId)

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
                "file_size" to prepared.size,
                "degraded" to degraded,
                "original_size" to fileSize,
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
        coroutineScope {
            ALL_NODES.map { node -> async { testNode(node) } }.awaitAll()
        }
    }

    /** Gọi REST của RTDB (timeout 4s, luôn đóng kết nối). body != null: gửi JSON UTF-8. */
    private inline fun <T> rest(url: String, method: String, body: String? = null, block: (HttpURLConnection) -> T): T {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = REST_TIMEOUT_MS
            conn.readTimeout = REST_TIMEOUT_MS
            conn.requestMethod = method
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            return block(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun testNode(node: FirebaseNode): NodeTestResult {
        return try {
            val start = System.currentTimeMillis()
            val code = rest("${node.url}/.json?shallow=true", "GET") { it.responseCode }
            val latency = System.currentTimeMillis() - start
            if (code in 200..299) NodeTestResult(node, true, latency) else NodeTestResult(node, false, latency, "HTTP $code")
        } catch (e: Exception) {
            NodeTestResult(node, false, 0, e.message ?: "Không phản hồi")
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
            listenerMap[listener] = ref
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
            val ref = listenerMap.remove(listener)
            ref?.removeEventListener(listener)
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi khi gỡ listener config: ${e.message}")
        }
    }

    /**
     * Lấy cấu hình một lần từ Firebase (/config)
     */
    suspend fun fetchConfigOnce(): Map<String, Any>? = withContext(Dispatchers.IO) {
        try {
            val text = rest("${getScheduledNode().url}/config.json", "GET") { conn ->
                if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
            }
            if (!text.isNullOrEmpty() && text != "null") {
                val mapType = object : TypeToken<Map<String, Any>>() {}.type
                Gson().fromJson<Map<String, Any>>(text, mapType)
            } else null
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
            // Gửi song song tới cả 4 cụm: máy offline không phải chờ lần lượt 8 lần timeout 4s
            coroutineScope {
                ALL_NODES.map { node -> async { sendConfigToNode(node, jsonPayload) } }.awaitAll().any { it }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi sendConfigUpdate: ${e.message}", e)
            false
        }
    }

    /** @return true nếu /config_update trên cụm này được ghi thành công. */
    private fun sendConfigToNode(node: FirebaseNode, jsonPayload: String): Boolean {
        val updated = try {
            val code = rest("${node.url}/config_update.json", "PUT", jsonPayload) { it.responseCode }
            Log.d(TAG, "Sent config_update to ${node.name}: code $code")
            code in 200..299
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send config_update to ${node.name}: ${e.message}")
            false
        }
        // Cập nhật trực tiếp /config.json để app không bị ghi đè ngược cấu hình cũ
        try {
            val code = rest("${node.url}/config.json", "PATCH", jsonPayload) { it.responseCode }
            Log.d(TAG, "Sent config patch to ${node.name}: code $code")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to patch config to ${node.name}: ${e.message}")
        }
        return updated
    }
}
