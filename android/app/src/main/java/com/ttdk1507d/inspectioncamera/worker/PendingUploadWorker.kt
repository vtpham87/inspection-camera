package com.ttdk1507d.inspectioncamera.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.ttdk1507d.inspectioncamera.firebase.FirebaseManager
import com.ttdk1507d.inspectioncamera.util.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File

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

    private val prefs = PrefsManager(appContext)
    private val gson = Gson()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val pendingDir = File(appContext.filesDir, "pending")
        if (!pendingDir.exists() || !pendingDir.isDirectory) {
            return@withContext Result.success()
        }

        val metaFiles = pendingDir.listFiles { file -> file.extension == "meta" } ?: emptyArray()
        if (metaFiles.isEmpty()) {
            return@withContext Result.success()
        }

        var anyFailed = false

        coroutineScope {
            val jobs = metaFiles.map { metaFile ->
                async {

            var imgFile: File? = null
            try {
                val json = metaFile.readText()
                val meta = gson.fromJson(json, PendingUploadMetadata::class.java)
                imgFile = File(pendingDir, meta.imageFileName)

                if (!imgFile.exists() || imgFile.length() == 0L) {
                    // Invalid/corrupted entry, clean up
                    metaFile.delete()
                    imgFile.delete()
                    return@async
                }

                // File > 7MB sẽ được FirebaseManager tự động nén

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
                } else {
                    anyFailed = true
                }
            } catch (e: Exception) {
                Log.e("PendingUploadWorker", "Lỗi xử lý upload metadata ${metaFile.name}: ${e.message}", e)
                anyFailed = true
            }
                } // async
            } // map
            jobs.awaitAll()
        } // coroutineScope

        if (anyFailed) {
            Result.retry()
        } else {
            Result.success()
        }
    }
}
