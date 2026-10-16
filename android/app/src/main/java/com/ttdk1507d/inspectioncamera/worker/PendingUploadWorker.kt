package com.ttdk1507d.inspectioncamera.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.ttdk1507d.inspectioncamera.firebase.FirebaseManager
import com.ttdk1507d.inspectioncamera.util.CacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class PendingUploadMetadata(
    @SerializedName("image_file_name") val imageFileName: String,
    val plate: String,
    @SerializedName("plate_color") val plateColor: String?,
    @SerializedName("photo_type") val photoType: String,
    val seq: Int? = null,
    @SerializedName("lan_kd") val lanKd: Int = 1,
    val timestamp: Long = System.currentTimeMillis()
)

class PendingUploadWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val gson = Gson()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val pendingDir = File(appContext.filesDir, "pending")
        if (!pendingDir.exists() || !pendingDir.isDirectory) {
            CacheManager.cleanUploadedFiles(appContext)
            return@withContext Result.success()
        }

        val metaFiles = pendingDir.listFiles { file -> file.extension == "meta" } ?: emptyArray()
        if (metaFiles.isEmpty()) {
            CacheManager.cleanUploadedFiles(appContext)
            return@withContext Result.success()
        }

        // Nhiều coroutine cùng ghi cờ này nên phải dùng AtomicBoolean (biến var thường sẽ bị race)
        val anyFailed = AtomicBoolean(false)
        val semaphore = Semaphore(2)

        coroutineScope {
            metaFiles.map { metaFile ->
                async {
                    semaphore.withPermit {
                        try {
                            if (!uploadOne(pendingDir, metaFile)) anyFailed.set(true)
                        } catch (e: Exception) {
                            Log.e(TAG, "Lỗi xử lý upload metadata ${metaFile.name}: ${e.message}", e)
                            anyFailed.set(true)
                        }
                    }
                }
            }.awaitAll()
        }

        // Xóa ảnh đã upload quá 15 phút (xe đã cấp tem)
        CacheManager.cleanUploadedFiles(appContext)

        if (anyFailed.get()) Result.retry() else Result.success()
    }

    /** @return true nếu xử lý xong (đã gửi hoặc dọn file hỏng), false nếu cần thử lại lần sau. */
    private suspend fun uploadOne(pendingDir: File, metaFile: File): Boolean {
        val meta = try {
            gson.fromJson(metaFile.readText(), PendingUploadMetadata::class.java)
        } catch (e: Exception) {
            null
        }
        // Gson bỏ qua kiểm tra null của Kotlin nên phải kiểm tra thủ công; file meta hỏng sẽ không bao giờ hết lỗi
        // nếu giữ lại -> xoá để Worker không retry vô hạn.
        if (meta == null || meta.imageFileName == null || meta.plate == null || meta.photoType == null) {
            Log.w(TAG, "Xoá metadata hỏng: ${metaFile.name}")
            metaFile.delete()
            return true
        }

        val imgFile = File(pendingDir, meta.imageFileName)
        if (!imgFile.exists()) {
            metaFile.delete()
            return true
        }
        if (imgFile.length() == 0L) {
            // File có thể đang được ghi; nếu rỗng quá 10 phút thì coi là hỏng và dọn
            if (System.currentTimeMillis() - imgFile.lastModified() > STALE_EMPTY_MS) {
                imgFile.delete()
                metaFile.delete()
                return true
            }
            return false
        }

        val success = FirebaseManager.uploadPhotoToInbox(
            plate = meta.plate,
            plateColor = meta.plateColor,
            photoType = meta.photoType,
            seq = meta.seq ?: 1,
            lanKd = meta.lanKd,
            photoFile = imgFile
        )
        if (success) {
            imgFile.delete()
            metaFile.delete()
        }
        return success
    }

    private companion object {
        const val TAG = "PendingUploadWorker"
        const val STALE_EMPTY_MS = 10 * 60 * 1000L
    }
}
