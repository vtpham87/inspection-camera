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
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
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
import com.ttdk1507d.inspectioncamera.util.PlateUtil
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.ttdk1507d.inspectioncamera.api.ApiClient
import com.ttdk1507d.inspectioncamera.firebase.FirebaseManager
import com.ttdk1507d.inspectioncamera.model.AppConfig
import com.ttdk1507d.inspectioncamera.model.PhotoType
import com.ttdk1507d.inspectioncamera.util.NetworkUtil
import com.ttdk1507d.inspectioncamera.util.PrefsManager
import java.util.concurrent.TimeUnit
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
        private const val TAG = "CameraActivity"
        const val EXTRA_PLATE = "extra_plate"
        const val EXTRA_PLATE_COLOR = "extra_plate_color"
        const val EXTRA_FOCUS_TYPE = "extra_focus_type"
        const val EXTRA_PHOTOS_TAKEN = "photos_taken"
        const val EXTRA_LAN_KD = "extra_lan_kd"
        private val gson = Gson()
    }

    private data class LocalPhotoInfo(val photoType: PhotoType, val seq: Int?)

    private lateinit var prefs: PrefsManager
    private var appConfig: AppConfig = AppConfig()

    private lateinit var plate: String
    private var plateColor: String? = null
    private var lanKd: Int = 1

    private lateinit var previewView: PreviewView
    private lateinit var tvPlate: TextView
    private lateinit var btnToggleLanKd: MaterialButton
    private lateinit var btnBack: ImageButton

    // AE/AF Views & State
    private var camera: Camera? = null
    private lateinit var ivFocusRing: ImageView
    private lateinit var tvAeAfLock: TextView
    private var isAeAfLocked = false
    private lateinit var scaleGestureDetector: ScaleGestureDetector

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
        val rawPlate = intent.getStringExtra(EXTRA_PLATE) ?: ""
        val resolved = PlateUtil.resolveFullPlate(rawPlate, intent.getStringExtra(EXTRA_PLATE_COLOR))
        plate = resolved.basePlate
        plateColor = resolved.color
        lanKd = intent.getIntExtra(EXTRA_LAN_KD, resolved.lanKd)

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
        intent?.getIntExtra(EXTRA_LAN_KD, lanKd)?.let {
            if (it != lanKd) {
                lanKd = it
                updateLanKdUI()
                refreshLocalPhotoStatus()
            }
        }
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
        btnToggleLanKd = findViewById(R.id.btn_toggle_lan_kd)
        btnBack = findViewById(R.id.btn_camera_back)

        ivFocusRing = findViewById(R.id.iv_camera_focus)
        tvAeAfLock = findViewById(R.id.tv_camera_ae_af_lock)

        btnRear45 = findViewById(R.id.btn_rear_45)
        btnFront45 = findViewById(R.id.btn_front_45)
        btnPassenger = findViewById(R.id.btn_passenger)
        btnNewVehicle = findViewById(R.id.btn_new_vehicle)
        btnReview = findViewById(R.id.btn_review)

        layoutLoading = findViewById(R.id.layout_camera_loading)
        tvLoadingText = findViewById(R.id.tv_camera_loading_text)

        updateLanKdUI()
    }

    private fun updateLanKdUI() {
        val compactPlate = PlateUtil.formatCompactPlate(plate, plateColor, lanKd)
        tvPlate.text = compactPlate
        if (lanKd > 1) {
            btnToggleLanKd.text = "Lần $lanKd"
            btnToggleLanKd.backgroundTintList = ContextCompat.getColorStateList(this, R.color.warning)
        } else {
            btnToggleLanKd.text = "Lần 1"
            btnToggleLanKd.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)
        }
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }

        btnToggleLanKd.setOnClickListener {
            lanKd = if (lanKd >= 3) 1 else lanKd + 1
            updateLanKdUI()
            refreshLocalPhotoStatus()
            Toast.makeText(this, "Đã chuyển sang: Lần $lanKd", Toast.LENGTH_SHORT).show()
        }

        btnRear45.setOnClickListener { takePhoto(PhotoType.REAR_45, null) }
        btnFront45.setOnClickListener { takePhoto(PhotoType.FRONT_45, null) }
        btnPassenger.setOnClickListener { takePhoto(PhotoType.PASSENGER, passengerSeq) }
        btnNewVehicle.setOnClickListener { takePhoto(PhotoType.NEW_VEHICLE, newVehicleSeq) }

        btnReview.setOnClickListener {
            val intent = Intent(this, ReviewActivity::class.java).apply {
                putExtra(ReviewActivity.EXTRA_PLATE, plate)
                putExtra(ReviewActivity.EXTRA_PLATE_COLOR, plateColor)
                putExtra(ReviewActivity.EXTRA_LAN_KD, lanKd)
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
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)

                setupTouchToFocus()
                setupOrientationListener()
            } catch (e: Exception) {
                Toast.makeText(this, "Lỗi mở camera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupTouchToFocus() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (isAeAfLocked) {
                    unlockAeAf()
                } else {
                    triggerFocusAndMetering(e.x, e.y, false)
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // Long press to LOCK AE/AF
                triggerFocusAndMetering(e.x, e.y, true)
            }
        })

        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val currentZoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                val delta = detector.scaleFactor
                camera?.cameraControl?.setZoomRatio(currentZoom * delta)
                return true
            }
        })

        previewView.setOnTouchListener { v, event ->
            scaleGestureDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            v.performClick()
            true
        }
    }

    private fun triggerFocusAndMetering(x: Float, y: Float, lock: Boolean) {
        val cam = camera ?: return
        val factory = previewView.meteringPointFactory
        val point = factory.createPoint(x, y)

        val builder = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
        if (lock) {
            builder.disableAutoCancel()
            isAeAfLocked = true
            tvAeAfLock.visibility = View.VISIBLE
        } else {
            builder.setAutoCancelDuration(3, TimeUnit.SECONDS)
            isAeAfLocked = false
            tvAeAfLock.visibility = View.GONE
        }

        vibrateTick()
        showFocusRing(x, y, lock)

        val action = builder.build()
        val future = cam.cameraControl.startFocusAndMetering(action)
        future.addListener({
            try {
                val result = future.get()
                runOnUiThread {
                    if (result.isFocusSuccessful) {
                        ivFocusRing.setColorFilter(ContextCompat.getColor(this, R.color.status_done_text))
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun unlockAeAf() {
        isAeAfLocked = false
        tvAeAfLock.visibility = View.GONE
        camera?.cameraControl?.cancelFocusAndMetering()
        vibrateTick()
        Toast.makeText(this, "Đã mở khóa AE/AF", Toast.LENGTH_SHORT).show()
    }

    private fun showFocusRing(x: Float, y: Float, lock: Boolean) {
        ivFocusRing.clearAnimation()
        ivFocusRing.clearColorFilter()
        ivFocusRing.x = x - (ivFocusRing.width / 2)
        ivFocusRing.y = y - (ivFocusRing.height / 2)
        ivFocusRing.visibility = View.VISIBLE
        ivFocusRing.alpha = 1.0f
        ivFocusRing.scaleX = 1.4f
        ivFocusRing.scaleY = 1.4f

        ivFocusRing.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(200)
            .withEndAction {
                if (!lock) {
                    ivFocusRing.animate()
                        .alpha(0f)
                        .setStartDelay(1500)
                        .setDuration(300)
                        .withEndAction {
                            ivFocusRing.visibility = View.GONE
                        }
                        .start()
                }
            }
            .start()
    }

    private fun vibrateTick() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(30)
            }
        } catch (e: Exception) {
            // Ignore
        }
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

    private fun refreshLocalPhotoStatus() {lifecycleScope.launch(Dispatchers.IO) {
        val reviewPhotos = mutableListOf<LocalPhotoInfo>()

        val reviewDir = if (lanKd > 1) File(filesDir, "review/$plate/$lanKd") else File(filesDir, "review/$plate")
        if (reviewDir.exists() && reviewDir.isDirectory) {
            val files = reviewDir.listFiles { f -> f.extension.equals("jpg", ignoreCase = true) } ?: emptyArray()
            for (file in files) {
                val name = file.nameWithoutExtension
                for (pt in PhotoType.ALL) {
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
            for (mf in metaFiles) {
                try {
                    val meta = gson.fromJson(mf.readText(), PendingUploadMetadata::class.java)
                    if (meta.plate.equals(plate, ignoreCase = true) && meta.lanKd == lanKd) {
                        val pt = PhotoType.fromApiName(meta.photoType)
                        if (pt != null) {
                            reviewPhotos.add(LocalPhotoInfo(pt, meta.seq))
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Lỗi đọc pending meta: ${mf.name}: ${e.message}")
                }
            }
        }

        
            withContext(Dispatchers.Main) {
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
        }
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
        var rawBitmap: Bitmap? = null
        var resizedBitmap: Bitmap? = null
        var stampedBitmap: Bitmap? = null
        try {
            rawBitmap = imageProxyToBitmap(imageProxy)

            // 1. Resize according to appConfig.photoResolution
            resizedBitmap = TimestampPainter.resizeBitmap(rawBitmap, appConfig.photoResolution)

            // 2. Draw timestamp if enabled
            stampedBitmap = if (appConfig.timestamp.enabled) {
                TimestampPainter.paintTimestamp(resizedBitmap, appConfig.timestamp)
            } else {
                null
            }

            val finalBitmap = stampedBitmap ?: resizedBitmap

            // 3. Compress to JPEG
            val jpegBytes = ByteArrayOutputStream().use { baos ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, appConfig.jpegQuality, baos)
                baos.toByteArray()
            }

            // 4. Save copy to local review cache on phone
            saveToLocalReview(type, seq, jpegBytes)

            refreshLocalPhotoStatus()
        } catch (e: Throwable) {
            Log.e(TAG, "Lỗi xử lý ảnh: ${e.message}", e)
            runOnUiThread {
                Toast.makeText(this, "Lỗi xử lý ảnh: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } finally {
            try {
                imageProxy.close()
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi đóng imageProxy: ${e.message}")
            }
            try {
                rawBitmap?.recycle()
                if (stampedBitmap != null && stampedBitmap != resizedBitmap) {
                    resizedBitmap?.recycle()
                    stampedBitmap?.recycle()
                } else {
                    resizedBitmap?.recycle()
                }
            } catch (e: Exception) {
                // Ignore recycle error
            }
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val buffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        
        var sampleSize = 1
        val maxDim = 3840 // enough for 4K limit before TimestampPainter resize
        while ((options.outWidth / sampleSize) > maxDim || (options.outHeight / sampleSize) > maxDim) {
            sampleSize *= 2
        }
        
        options.inJustDecodeBounds = false
        options.inSampleSize = sampleSize
        
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalStateException("Không thể decode bitmap từ ImageProxy")
            
        val rotation = imageProxy.imageInfo.rotationDegrees
        return if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) { bitmap.recycle() }
            rotated
        } else {
            bitmap
        }
    }

    private fun vibrateSuccess() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    vibrator?.vibrate(50)
                }
            }
        } catch (e: Exception) {
            // Ignore vibration errors on devices without vibrator
        }
    }

    private fun saveToLocalReview(type: PhotoType, seq: Int?, bytes: ByteArray) {
        try {
            val reviewDir = if (lanKd > 1) File(filesDir, "review/$plate/$lanKd") else File(filesDir, "review/$plate")
            if (!reviewDir.exists()) reviewDir.mkdirs()
            val fileName = if (seq != null && type.multiPhoto) {
                "${type.apiName}_$seq.jpg"
            } else {
                "${type.apiName}.jpg"
            }
            val targetFile = File(reviewDir, fileName)
            FileOutputStream(targetFile).use { it.write(bytes) }
            prefs.clearPhotoUploaded(plate, lanKd, fileName)
            prefs.clearPlateDoneToday(plate, lanKd)
        } catch (e: Exception) {
            // Non-critical cache
        }
    }
}
