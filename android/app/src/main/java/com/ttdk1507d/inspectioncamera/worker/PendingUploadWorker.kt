package com.ttdk1507d.inspectioncamera.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.ttdk1507d.inspectioncamera.api.ApiClient
import com.ttdk1507d.inspectioncamera.util.NetworkUtil
import com.ttdk1507d.inspectioncamera.util.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

data class PendingUploadMetadata(
    @SerializedName("image_file_name") val imageFileName: String,
    val plate: String,
    @SerializedName("plate_color") val plateColor: String?,
    @SerializedName("photo_type") val photoType: String,
    val seq: Int? = null,
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

        for (metaFile in metaFiles) {
            try {
                val json = metaFile.readText()
                val meta = gson.fromJson(json, PendingUploadMetadata::class.java)
                val imgFile = File(pendingDir, meta.imageFileName)

                if (!imgFile.exists() || imgFile.length() == 0L) {
                    // Invalid/corrupted entry, clean up
                    metaFile.delete()
                    imgFile.delete()
                    continue
                }

                val baseUrl = NetworkUtil.resolveBaseUrl(prefs.lanUrl, prefs.tailscaleUrl)
                val service = ApiClient.getService(baseUrl)

                val fileReq = imgFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
                val filePart = MultipartBody.Part.createFormData("file", imgFile.name, fileReq)
                val plateReq = meta.plate.toRequestBody("text/plain".toMediaTypeOrNull())
                val photoTypeReq = meta.photoType.toRequestBody("text/plain".toMediaTypeOrNull())
                val colorReq = meta.plateColor?.toRequestBody("text/plain".toMediaTypeOrNull())
                val seqReq = meta.seq?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())

                val resp = service.uploadPhoto(filePart, plateReq, colorReq, photoTypeReq, seqReq)

                if (resp.isSuccessful && resp.body()?.get("ok") == true) {
                    // Uploaded successfully, remove from queue
                    imgFile.delete()
                    metaFile.delete()
                } else {
                    anyFailed = true
                }
            } catch (e: Exception) {
                anyFailed = true
            }
        }

        if (anyFailed) {
            Result.retry()
        } else {
            Result.success()
        }
    }
}
