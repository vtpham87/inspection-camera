package com.ttdk1507d.inspectioncamera.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.ttdk1507d.inspectioncamera.api.ApiClient
import com.ttdk1507d.inspectioncamera.firebase.FirebaseManager
import com.ttdk1507d.inspectioncamera.util.NetworkUtil
import com.ttdk1507d.inspectioncamera.util.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
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

        val baseUrl = NetworkUtil.resolveBaseUrl(prefs)
        val service = if (baseUrl != null) ApiClient.getService(baseUrl) else null

        if (service == null && !prefs.firebaseEnabled) {
            return@withContext Result.retry()
        }

        for (metaFile in metaFiles) {
            var imgFile: File? = null
            try {
                val json = metaFile.readText()
                val meta = gson.fromJson(json, PendingUploadMetadata::class.java)
                imgFile = File(pendingDir, meta.imageFileName)

                if (!imgFile.exists() || imgFile.length() == 0L) {
                    // Invalid/corrupted entry, clean up
                    metaFile.delete()
                    imgFile.delete()
                    continue
                }

                if (service != null) {
                    val fileReq = imgFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
                    val filePart = MultipartBody.Part.createFormData("file", imgFile.name, fileReq)
                    val plateReq = meta.plate.toRequestBody("text/plain".toMediaTypeOrNull())
                    val photoTypeReq = meta.photoType.toRequestBody("text/plain".toMediaTypeOrNull())
                    val colorReq = meta.plateColor?.toRequestBody("text/plain".toMediaTypeOrNull())
                    val seqReq = meta.seq?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())
                    val lanKdReq = meta.lanKd.toString().toRequestBody("text/plain".toMediaTypeOrNull())

                    val resp = service.uploadPhoto(filePart, plateReq, colorReq, photoTypeReq, seqReq, lanKdReq)

                    if (resp.isSuccessful && resp.body()?.get("ok") == true) {
                        // Uploaded successfully, remove from queue
                        imgFile.delete()
                        metaFile.delete()
                    } else if (resp.code() in 400..499) {
                        // Server returned 4xx client error (do not infinite retry on 400)
                        imgFile.delete()
                        metaFile.delete()
                    } else {
                        anyFailed = true
                    }
                } else if (prefs.firebaseEnabled) {
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
                }
            } catch (e: HttpException) {
                if (e.code() in 400..499) {
                    imgFile?.delete()
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
