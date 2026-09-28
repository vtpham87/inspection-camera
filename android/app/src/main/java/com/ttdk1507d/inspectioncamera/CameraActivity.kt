package com.ttdk1507d.inspectioncamera

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
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
import androidx.work.ExistingWorkPolicy
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
    private lateinit var btnBack: ImageButton

    private lateinit var btnRear45: MaterialButton
    private lateinit var btnFront45: MaterialButton
    private lateinit var btnPassenger: MaterialButton
    private lateinit var btnNewVehicle: MaterialButton
    private lateinit var btnReview: MaterialButton

    private lateinit var layoutLoading: FrameLayout
    private lateinit var tvLoadingText: TextView

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var orientationEventListener: OrientationEventListener? = null

    // Track state of captured photos for current plate
    private val capturedStatus = mutableMapOf<PhotoType, Boolean>()
    private var passengerSeq = 1
    private var newVehicleSeq = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
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
        appConfig = prefs.getAppConfig()
        refreshLocalPhotoStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        orientationEventListener?.disable()
        cameraExecutor.shutdown()
    }

    private fun initViews() {
        previewView = findViewById(R.id.preview_view)
        tvPlate = findViewById(R.id.tv_camera_plate)
        btnBack = findViewById(R.id.btn_camera_back)

        btnRear45 = findViewById(R.id.btn_rear_45)
        btnFront45 = findViewById(R.id.btn_front_45)
        btnPassenger = findViewById(R.id.btn_passenger)
        btnNewVehicle = findViewById(R.id.btn_new_vehicle)
        btnReview = findViewById(R.id.btn_review)

        layoutLoading = findViewById(R.id.layout_camera_loading)
        tvLoadingText = findViewById(R.id.tv_camera_loading_text)

        val colorText = when (plateColor?.uppercase()) {
            "T" -> " - Biển trắng"
            "V" -> " - Biển vàng"
            "X" -> " - Biển xanh"
            else -> ""
        }
        tvPlate.text = "$plate$colorText"
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }

        btnRear45.setOnClickListener { takePhoto(PhotoType.REAR_45, null) }
        btnFront45.setOnClickListener { takePhoto(PhotoType.FRONT_45, null) }
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
                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                    .build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                    .build()

                val displayRotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    display?.rotation ?: Surface.ROTATION_0
                } else {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay.rotation
                }
                imageCapture?.targetRotation = displayRotation

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)

                setupOrientationListener()
            } catch (e: Exception) {
                Toast.makeText(this, "Lỗi mở camera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupOrientationListener() {
        orientationEventListener?.disable()
        orientationEventListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                imageCapture?.targetRotation = rotation
            }
        }
        if (orientationEventListener?.canDetectOrientation() == true) {
            orientationEventListener?.enable()
        }
    }

    private fun loadConfigAndState() {
        appConfig = prefs.getAppConfig()
        refreshLocalPhotoStatus()
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

        val existingPhotos = reviewPhotos.distinct()

        for (pt in PhotoType.values()) {
            capturedStatus[pt] = existingPhotos.any { it.photoType == pt }
        }

        passengerSeq = (existingPhotos.filter { it.photoType == PhotoType.PASSENGER }.mapNotNull { it.seq }.maxOrNull() ?: 0) + 1
        newVehicleSeq = (existingPhotos.filter { it.photoType == PhotoType.NEW_VEHICLE }.mapNotNull { it.seq }.maxOrNull() ?: 0) + 1

        val passengerCount = existingPhotos.count { it.photoType == PhotoType.PASSENGER }
        val newVehicleCount = existingPhotos.count { it.photoType == PhotoType.NEW_VEHICLE }

        updateButtonUI(btnRear45, PhotoType.REAR_45, capturedStatus[PhotoType.REAR_45] == true)
        updateButtonUI(btnFront45, PhotoType.FRONT_45, capturedStatus[PhotoType.FRONT_45] == true)
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

        // 1. Instant tactile feedback
        vibrateSuccess()

        // 2. Optimistic UI update: immediately mark button as captured
        capturedStatus[type] = true
        when (type) {
            PhotoType.REAR_45 -> updateButtonUI(btnRear45, PhotoType.REAR_45, true)
            PhotoType.FRONT_45 -> updateButtonUI(btnFront45, PhotoType.FRONT_45, true)
            PhotoType.PASSENGER -> {
                passengerSeq++
                updateButtonUI(btnPassenger, PhotoType.PASSENGER, true, passengerSeq - 1)
            }
            PhotoType.NEW_VEHICLE -> {
                newVehicleSeq++
                updateButtonUI(btnNewVehicle, PhotoType.NEW_VEHICLE, true, newVehicleSeq - 1)
            }
            else -> {}
        }

        Toast.makeText(this, "📸 Đã chụp: ${type.label}", Toast.LENGTH_SHORT).show()

        // 3. Capture in background thread without blocking camera preview
        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    processAndSave(imageProxy, type, seq)
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread {
                        Toast.makeText(this@CameraActivity, "Chụp ảnh thất bại: ${exception.message}", Toast.LENGTH_SHORT).show()
                        refreshLocalPhotoStatus()
                    }
                }
            }
        )
    }

    private fun processAndSave(imageProxy: ImageProxy, type: PhotoType, seq: Int?) {
        try {
            val rawBitmap = imageProxyToBitmap(imageProxy)
            imageProxy.close()

            // 1. Resize according to appConfig.photoResolution
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

            // 4. Save copy to local review cache on phone
            saveToLocalReview(type, seq, jpegBytes)

            // 5. Save to pending queue for upload
            saveToPendingQueue(type, seq, jpegBytes)

            // 6. If uploadMode is "immediate", trigger background upload
            if (prefs.uploadMode == "immediate") {
                triggerBackgroundUpload(type, seq, jpegBytes)
            }

            runOnUiThread {
                refreshLocalPhotoStatus()
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Lỗi xử lý ảnh: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveToPendingQueue(type: PhotoType, seq: Int?, bytes: ByteArray) {
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
        } catch (e: Exception) {
            // Non-critical queue write
        }
    }

    private fun triggerBackgroundUpload(type: PhotoType, seq: Int?, bytes: ByteArray) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val baseUrl = NetworkUtil.resolveBaseUrl(prefs.lanUrl, prefs.tailscaleUrl) ?: return@launch
                val service = ApiClient.getService(baseUrl)

                val fileReq = bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                val filePart = MultipartBody.Part.createFormData("file", "upload.jpg", fileReq)
                val plateReq = plate.toRequestBody("text/plain".toMediaTypeOrNull())
                val photoTypeReq = type.apiName.toRequestBody("text/plain".toMediaTypeOrNull())
                val colorReq = plateColor?.toRequestBody("text/plain".toMediaTypeOrNull())
                val seqReq = seq?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())

                val resp = service.uploadPhoto(filePart, plateReq, colorReq, photoTypeReq, seqReq)
                if (resp.isSuccessful && resp.body()?.get("ok") == true) {
                    val pendingDir = File(filesDir, "pending")
                    val metaFiles = pendingDir.listFiles { f -> f.extension == "meta" } ?: emptyArray()
                    val gson = Gson()
                    for (mf in metaFiles) {
                        try {
                            val meta = gson.fromJson(mf.readText(), PendingUploadMetadata::class.java)
                            if (meta.plate == plate && meta.photoType == type.apiName && meta.seq == seq) {
                                File(pendingDir, meta.imageFileName).delete()
                                mf.delete()
                            }
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                }
            } catch (e: Exception) {
                // Kept in pending queue
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
}
