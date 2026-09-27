package com.ttdk1507d.inspectioncamera

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.ttdk1507d.inspectioncamera.api.ApiClient
import com.ttdk1507d.inspectioncamera.model.AppConfig
import com.ttdk1507d.inspectioncamera.model.PhotoType
import com.ttdk1507d.inspectioncamera.util.NetworkUtil
import com.ttdk1507d.inspectioncamera.util.PrefsManager
import com.ttdk1507d.inspectioncamera.util.TimestampPainter
import com.ttdk1507d.inspectioncamera.worker.PendingUploadMetadata
import com.ttdk1507d.inspectioncamera.worker.PendingUploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLATE = "extra_plate"
        const val EXTRA_PLATE_COLOR = "extra_plate_color"
        const val EXTRA_FOCUS_TYPE = "extra_focus_type"
        const val EXTRA_PHOTOS_TAKEN = "photos_taken"
    }

    private data class LocalPhotoInfo(val photoType: PhotoType, val seq: Int?)

    private lateinit var prefs: PrefsManager
    private var appConfig: AppConfig = AppConfig()

    private lateinit var plate: String
    private var plateColor: String? = null

    private lateinit var previewView: PreviewView
    private lateinit var tvPlate: TextView
    private lateinit var tvPlateColor: TextView
    private lateinit var btnBack: ImageButton

    private lateinit var btnRear45: MaterialButton
    private lateinit var btnFront45: MaterialButton
    private lateinit var btnChassis: MaterialButton
    private lateinit var btnPassenger: MaterialButton
    private lateinit var btnNewVehicle: MaterialButton
    private lateinit var btnReview: MaterialButton

    private lateinit var layoutLoading: FrameLayout
    private lateinit var tvLoadingText: TextView

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    // Track state of captured photos for current plate
    private val capturedStatus = mutableMapOf<PhotoType, Boolean>()
    private var passengerSeq = 1
    private var newVehicleSeq = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        prefs = PrefsManager(this)
        plate = intent.getStringExtra(EXTRA_PLATE) ?: ""
        plateColor = intent.getStringExtra(EXTRA_PLATE_COLOR)

        if (plate.isEmpty()) {
            Toast.makeText(this, "Thiếu thông tin biển số", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val initialPhotos = intent.getStringArrayListExtra(EXTRA_PHOTOS_TAKEN)
        if (initialPhotos != null) {
            for (name in initialPhotos) {
                val pt = PhotoType.values().firstOrNull { it.apiName == name }
                if (pt != null) {
                    capturedStatus[pt] = true
                }
            }
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        initViews()
        setupListeners()
        loadConfigAndState()
        startCamera()
        intent.getStringExtra(EXTRA_FOCUS_TYPE)?.let { handleFocusType(it) }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.getStringExtra(EXTRA_FOCUS_TYPE)?.let { handleFocusType(it) }
    }

    private fun handleFocusType(focusType: String) {
        val button = when (focusType) {
            PhotoType.REAR_45.apiName -> btnRear45
            PhotoType.FRONT_45.apiName -> btnFront45
            PhotoType.CHASSIS.apiName -> btnChassis
            PhotoType.PASSENGER.apiName -> btnPassenger
            PhotoType.NEW_VEHICLE.apiName -> btnNewVehicle
            else -> null
        } ?: return

        button.requestFocus()
        val pt = PhotoType.values().firstOrNull { it.apiName == focusType }
        if (pt != null) {
            Toast.makeText(this, "Chụp lại: ${pt.label}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLocalPhotoStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun initViews() {
        previewView = findViewById(R.id.preview_view)
        tvPlate = findViewById(R.id.tv_camera_plate)
        tvPlateColor = findViewById(R.id.tv_camera_plate_color)
        btnBack = findViewById(R.id.btn_camera_back)

        btnRear45 = findViewById(R.id.btn_rear_45)
        btnFront45 = findViewById(R.id.btn_front_45)
        btnChassis = findViewById(R.id.btn_chassis)
        btnPassenger = findViewById(R.id.btn_passenger)
        btnNewVehicle = findViewById(R.id.btn_new_vehicle)
        btnReview = findViewById(R.id.btn_review)

        layoutLoading = findViewById(R.id.layout_camera_loading)
        tvLoadingText = findViewById(R.id.tv_camera_loading_text)

        tvPlate.text = plate
        when (plateColor?.uppercase()) {
            "T" -> {
                tvPlateColor.visibility = View.VISIBLE
                tvPlateColor.text = "Trắng (T)"
            }
            "V" -> {
                tvPlateColor.visibility = View.VISIBLE
                tvPlateColor.text = "Vàng (V)"
            }
            "X" -> {
                tvPlateColor.visibility = View.VISIBLE
                tvPlateColor.text = "Xanh (X)"
            }
            else -> {
                tvPlateColor.visibility = View.GONE
            }
        }
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }

        btnRear45.setOnClickListener { takePhoto(PhotoType.REAR_45, null) }
        btnFront45.setOnClickListener { takePhoto(PhotoType.FRONT_45, null) }
        btnChassis.setOnClickListener { takePhoto(PhotoType.CHASSIS, null) }
        btnPassenger.setOnClickListener { takePhoto(PhotoType.PASSENGER, passengerSeq) }
        btnNewVehicle.setOnClickListener { takePhoto(PhotoType.NEW_VEHICLE, newVehicleSeq) }

        btnReview.setOnClickListener {
            val intent = Intent(this, ReviewActivity::class.java).apply {
                putExtra(ReviewActivity.EXTRA_PLATE, plate)
                putExtra(ReviewActivity.EXTRA_PLATE_COLOR, plateColor)
            }
            startActivity(intent)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (e: Exception) {
                Toast.makeText(this, "Lỗi mở camera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun loadConfigAndState() {
        lifecycleScope.launch {
            try {
                val baseUrl = NetworkUtil.resolveBaseUrl(prefs.lanUrl, prefs.tailscaleUrl)
                val service = ApiClient.getService(baseUrl)
                val configResp = withContext(Dispatchers.IO) { service.getConfig() }
                if (configResp.isSuccessful && configResp.body() != null) {
                    val gson = Gson()
                    val jsonStr = gson.toJson(configResp.body())
                    appConfig = gson.fromJson(jsonStr, AppConfig::class.java)
                }
            } catch (e: Exception) {
                // Use default AppConfig
            }
            refreshLocalPhotoStatus()
        }
    }

    private fun refreshLocalPhotoStatus() {
        val reviewPhotos = mutableListOf<LocalPhotoInfo>()

        val reviewDir = File(filesDir, "review/$plate")
        if (reviewDir.exists() && reviewDir.isDirectory) {
            val files = reviewDir.listFiles { f -> f.extension.equals("jpg", ignoreCase = true) } ?: emptyArray()
            for (file in files) {
                val name = file.nameWithoutExtension
                for (pt in PhotoType.values()) {
                    if (name.startsWith(pt.apiName)) {
                        val rem = name.removePrefix(pt.apiName).removePrefix("_")
                        val seq = rem.toIntOrNull()
                        reviewPhotos.add(LocalPhotoInfo(pt, seq))
                        break
                    }
                }
            }
        }

        val pendingDir = File(filesDir, "pending")
        if (pendingDir.exists() && pendingDir.isDirectory) {
            val metaFiles = pendingDir.listFiles { f -> f.extension == "meta" } ?: emptyArray()
            val gson = Gson()
            for (mf in metaFiles) {
                try {
                    val meta = gson.fromJson(mf.readText(), PendingUploadMetadata::class.java)
                    if (meta.plate.equals(plate, ignoreCase = true)) {
                        val pt = PhotoType.values().firstOrNull { it.apiName == meta.photoType }
                        if (pt != null) {
                            reviewPhotos.add(LocalPhotoInfo(pt, meta.seq))
                        }
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }

        val existingPhotos = reviewPhotos

        for (pt in PhotoType.values()) {
            capturedStatus[pt] = capturedStatus[pt] == true || existingPhotos.any { it.photoType == pt }
        }

        passengerSeq = (existingPhotos.filter { it.photoType == PhotoType.PASSENGER }.mapNotNull { it.seq }.maxOrNull() ?: 0) + 1
        newVehicleSeq = (existingPhotos.filter { it.photoType == PhotoType.NEW_VEHICLE }.mapNotNull { it.seq }.maxOrNull() ?: 0) + 1

        val passengerCount = existingPhotos.count { it.photoType == PhotoType.PASSENGER }
        val newVehicleCount = existingPhotos.count { it.photoType == PhotoType.NEW_VEHICLE }

        updateButtonUI(btnRear45, PhotoType.REAR_45, capturedStatus[PhotoType.REAR_45] == true)
        updateButtonUI(btnFront45, PhotoType.FRONT_45, capturedStatus[PhotoType.FRONT_45] == true)
        updateButtonUI(btnChassis, PhotoType.CHASSIS, capturedStatus[PhotoType.CHASSIS] == true)
        updateButtonUI(btnPassenger, PhotoType.PASSENGER, capturedStatus[PhotoType.PASSENGER] == true, passengerCount)
        updateButtonUI(btnNewVehicle, PhotoType.NEW_VEHICLE, capturedStatus[PhotoType.NEW_VEHICLE] == true, newVehicleCount)
    }

    private fun updateButtonUI(button: MaterialButton, type: PhotoType, isDone: Boolean, count: Int = 0) {
        if (isDone) {
            button.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_done_bg)
            button.setStrokeColorResource(R.color.status_done_stroke)
            button.setTextColor(ContextCompat.getColor(this, R.color.status_done_text))
            val text = if (type.multiPhoto && count > 0) {
                "✓ ${type.label} ($count)"
            } else {
                "✓ ${type.label}"
            }
            button.text = text
        } else {
            button.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_empty_bg)
            button.setStrokeColorResource(R.color.status_empty_stroke)
            button.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            button.text = type.label
        }
    }

    private fun takePhoto(type: PhotoType, seq: Int?) {
        val capture = imageCapture ?: return

        layoutLoading.visibility = View.VISIBLE
        tvLoadingText.text = getString(R.string.uploading)

        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    processAndUpload(imageProxy, type, seq)
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread {
                        layoutLoading.visibility = View.GONE
                        Toast.makeText(this@CameraActivity, "Chụp ảnh thất bại: ${exception.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun processAndUpload(imageProxy: ImageProxy, type: PhotoType, seq: Int?) {
        try {
            val rawBitmap = imageProxyToBitmap(imageProxy)
            imageProxy.close()

            // 1. Resize according to photo_resolution
            val resizedBitmap = TimestampPainter.resizeBitmap(rawBitmap, appConfig.photoResolution)

            // 2. Draw timestamp if enabled
            val stampedBitmap = if (appConfig.timestamp.enabled) {
                TimestampPainter.paintTimestamp(resizedBitmap, appConfig.timestamp)
            } else {
                resizedBitmap
            }

            // 3. Compress to JPEG
            val baos = ByteArrayOutputStream()
            stampedBitmap.compress(Bitmap.CompressFormat.JPEG, appConfig.jpegQuality, baos)
            val jpegBytes = baos.toByteArray()

            // 4. Haptic feedback (50ms vibration)
            vibrateSuccess()

            // 5. Save copy to local review cache
            saveToLocalReview(type, seq, jpegBytes)

            // 6. Upload or queue
            uploadPhotoBytes(type, seq, jpegBytes)
        } catch (e: Exception) {
            runOnUiThread {
                layoutLoading.visibility = View.GONE
                Toast.makeText(this, "Lỗi xử lý ảnh: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val buffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val rotation = imageProxy.imageInfo.rotationDegrees
        return if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    }

    private fun vibrateSuccess() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    vibrator.vibrate(50)
                }
            }
        } catch (e: Exception) {
            // Ignore vibration errors on devices without vibrator
        }
    }

    private fun saveToLocalReview(type: PhotoType, seq: Int?, bytes: ByteArray) {
        try {
            val reviewDir = File(filesDir, "review/$plate")
            if (!reviewDir.exists()) reviewDir.mkdirs()
            val fileName = if (seq != null && type.multiPhoto) {
                "${type.apiName}_$seq.jpg"
            } else {
                "${type.apiName}.jpg"
            }
            val targetFile = File(reviewDir, fileName)
            FileOutputStream(targetFile).use { it.write(bytes) }
        } catch (e: Exception) {
            // Non-critical cache
        }
    }

    private fun uploadPhotoBytes(type: PhotoType, seq: Int?, bytes: ByteArray) {
        lifecycleScope.launch {
            var uploadSuccess = false
            try {
                val baseUrl = NetworkUtil.resolveBaseUrl(prefs.lanUrl, prefs.tailscaleUrl)
                val service = ApiClient.getService(baseUrl)

                val fileReq = bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                val filePart = MultipartBody.Part.createFormData("file", "upload.jpg", fileReq)
                val plateReq = plate.toRequestBody("text/plain".toMediaTypeOrNull())
                val photoTypeReq = type.apiName.toRequestBody("text/plain".toMediaTypeOrNull())
                val colorReq = plateColor?.toRequestBody("text/plain".toMediaTypeOrNull())
                val seqReq = seq?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())

                val resp = withContext(Dispatchers.IO) {
                    service.uploadPhoto(filePart, plateReq, colorReq, photoTypeReq, seqReq)
                }

                if (resp.isSuccessful && resp.body()?.get("ok") == true) {
                    uploadSuccess = true
                }
            } catch (e: Exception) {
                uploadSuccess = false
            }

            layoutLoading.visibility = View.GONE

            if (uploadSuccess) {
                Toast.makeText(this@CameraActivity, getString(R.string.upload_success), Toast.LENGTH_SHORT).show()
            } else {
                saveToOfflineQueue(type, seq, bytes)
                Toast.makeText(this@CameraActivity, getString(R.string.upload_offline), Toast.LENGTH_LONG).show()
            }

            refreshLocalPhotoStatus()
        }
    }

    private suspend fun saveToOfflineQueue(type: PhotoType, seq: Int?, bytes: ByteArray) = withContext(Dispatchers.IO) {
        try {
            val pendingDir = File(filesDir, "pending")
            if (!pendingDir.exists()) pendingDir.mkdirs()

            val timestamp = System.currentTimeMillis()
            val imgFile = File(pendingDir, "pending_${timestamp}_${type.apiName}.jpg")
            val metaFile = File(pendingDir, "pending_${timestamp}_${type.apiName}.meta")

            FileOutputStream(imgFile).use { it.write(bytes) }

            val meta = PendingUploadMetadata(
                imageFileName = imgFile.name,
                plate = plate,
                plateColor = plateColor,
                photoType = type.apiName,
                seq = seq,
                timestamp = timestamp
            )
            val metaJson = Gson().toJson(meta)
            metaFile.writeText(metaJson)

            // Trigger worker
            val oneTimeRequest = OneTimeWorkRequestBuilder<PendingUploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(this@CameraActivity).enqueue(oneTimeRequest)
        } catch (e: Exception) {
            // Failed to save to pending queue
        }
    }
}
