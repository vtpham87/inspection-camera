package com.ttdk1507d.inspectioncamera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.Gson
import com.ttdk1507d.inspectioncamera.adapter.VehicleAdapter
import com.ttdk1507d.inspectioncamera.api.ApiClient
import com.ttdk1507d.inspectioncamera.firebase.FirebaseManager
import com.ttdk1507d.inspectioncamera.model.Vehicle
import com.ttdk1507d.inspectioncamera.util.NetworkUtil
import com.ttdk1507d.inspectioncamera.util.PlateUtil
import com.ttdk1507d.inspectioncamera.util.PrefsManager
import com.ttdk1507d.inspectioncamera.worker.PendingUploadMetadata
import com.ttdk1507d.inspectioncamera.worker.PendingUploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CAMERA = 1001
        private const val AUTO_REFRESH_INTERVAL_MS = 2 * 60 * 1000L // 2 phút quét danh sách
    }

    private lateinit var prefs: PrefsManager
    private lateinit var vehicleAdapter: VehicleAdapter
    private var currentVehiclesList: List<Vehicle> = emptyList()
    private var autoRefreshJob: Job? = null

    private lateinit var btnSettings: ImageButton
    private lateinit var tvPendingBanner: TextView
    private lateinit var tilPlate: TextInputLayout
    private lateinit var etPlate: TextInputEditText
    private lateinit var rgPlateColor: RadioGroup
    private lateinit var rgLanKd: RadioGroup
    private lateinit var rbLan1: RadioButton
    private lateinit var rbLan2: RadioButton
    private lateinit var btnSelectManual: MaterialButton

    private lateinit var layoutVehicleListContainer: View
    private lateinit var btnFilterWaiting: MaterialButton
    private lateinit var btnFilterAll: MaterialButton
    private var isFilterWaiting = true
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var rvVehicles: RecyclerView
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvEmpty: TextView
    private var firebaseJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = PrefsManager(this)

        initViews()
        setupListeners()
        setupRecyclerView()
        checkPermissions()
        scheduleOfflineWorker()
        observeFirebaseConfig()
    }

    override fun onResume() {
        super.onResume()
        updatePendingBanner()
        applyVehicleListVisibility()
    }

    override fun onPause() {
        super.onPause()
        stopPeriodicRefresh()
        firebaseJob?.cancel()
        firebaseJob = null
    }

    private fun startPeriodicRefresh() {
        stopPeriodicRefresh()
        autoRefreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(AUTO_REFRESH_INTERVAL_MS)
                if (prefs.vehicleListEnabled && !prefs.firebaseEnabled) {
                    loadVehicles(silent = true)
                }
            }
        }
    }

    private fun stopPeriodicRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    private var firebaseConfigListener: com.google.firebase.database.ValueEventListener? = null

    private fun observeFirebaseConfig() {
        firebaseConfigListener = FirebaseManager.observeConfig { configMap ->
            try {
                val photoSaveDir = configMap["photo_save_dir"] as? String
                if (!photoSaveDir.isNullOrBlank()) {
                    prefs.photoSaveDir = photoSaveDir
                }
                val passengerPath = configMap["passenger_path"] as? String
                if (!passengerPath.isNullOrBlank()) {
                    prefs.passengerPath = passengerPath
                }
                val newVehiclePath = configMap["new_vehicle_path"] as? String
                if (!newVehiclePath.isNullOrBlank()) {
                    prefs.newVehiclePath = newVehiclePath
                }
                val syncNewVehicle45 = configMap["sync_new_vehicle_45"] as? Boolean
                if (syncNewVehicle45 != null) {
                    prefs.syncNewVehicle45 = syncNewVehicle45
                }
                val photoResolution = configMap["photo_resolution"] as? String
                if (!photoResolution.isNullOrBlank()) {
                    prefs.photoResolution = photoResolution
                }
                val jpegQuality = (configMap["jpeg_quality"] as? Number)?.toInt()
                if (jpegQuality != null && jpegQuality > 0) {
                    prefs.jpegQuality = jpegQuality
                }
                @Suppress("UNCHECKED_CAST")
                val timestamp = configMap["timestamp"] as? Map<String, Any>
                if (timestamp != null) {
                    (timestamp["enabled"] as? Boolean)?.let { prefs.timestampEnabled = it }
                    (timestamp["format"] as? String)?.let { prefs.timestampFormat = it }
                    (timestamp["position"] as? String)?.let { prefs.timestampPosition = it }
                    (timestamp["font_size"] as? Number)?.let { prefs.timestampFontSize = it.toInt() }
                }
                Log.d("MainActivity", "Đã nhận cấu hình từ máy tính qua Firebase: saveDir=${prefs.photoSaveDir}")
            } catch (e: Exception) {
                Log.w("MainActivity", "Lỗi nạp config từ Firebase: ${e.message}")
            }
        }
    }

    private fun observeFirebaseVehicles() {
        firebaseJob?.cancel()
        firebaseJob = lifecycleScope.launch {
            if (currentVehiclesList.isEmpty()) {
                pbLoading.visibility = View.VISIBLE
                tvEmpty.visibility = View.GONE
            }
            FirebaseManager.observeVehicles().collect { list ->
                pbLoading.visibility = View.GONE
                swipeRefresh.isRefreshing = false
                currentVehiclesList = list
                applyCurrentFilter()

                // Cập nhật lại Lần 1 / Lần 2 nếu người dùng đang nhập dở biển số
                val currentPlate = etPlate.text?.toString()?.trim().orEmpty()
                if (currentPlate.isNotEmpty()) {
                    val clean = currentPlate.replace(Regex("[.\\-\\s]"), "").uppercase()
                    val (base, _) = PlateUtil.extractColor(clean)
                    if (isPlateDataCompleteToday(base, clean)) {
                        rgLanKd.check(R.id.rb_lan_2)
                    }
                }
            }
        }
    }

    private fun applyCurrentFilter() {
        val filtered = if (isFilterWaiting) {
            currentVehiclesList
                .filter { vehicle -> !isVehicleFinished(vehicle) }
                .sortedWith(Comparator { v1, v2 ->
                    val t1 = v1.getEffectiveTicketInt()
                    val t2 = v2.getEffectiveTicketInt()
                    if (t1 != t2) t1.compareTo(t2) else v1.time.compareTo(v2.time)
                })
        } else {
            currentVehiclesList
                .filter { vehicle -> isVehicleHasPhotos(vehicle) }
                .map { vehicle ->
                    if (vehicle.photosTaken.isEmpty()) {
                        val targetPlate = if (vehicle.plateClean.isNotBlank()) vehicle.plateClean else vehicle.plate
                        val count = getLocalPhotoCount(targetPlate, vehicle.lanKd)
                        if (count > 0) {
                            vehicle.copy(photosTaken = List(count) { "local_$it" })
                        } else vehicle
                    } else vehicle
                }
                .sortedWith(Comparator { v1, v2 ->
                    val t1 = v1.getEffectiveTicketInt()
                    val t2 = v2.getEffectiveTicketInt()
                    if (t1 != t2) t2.compareTo(t1) else v2.time.compareTo(v1.time)
                })
        }
        vehicleAdapter.updateList(filtered)
        if (filtered.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = if (isFilterWaiting) {
                "Không có xe nào đang chờ\n(Đã hoàn thành kiểm định hoặc chụp ảnh xong)"
            } else {
                "Hôm nay chưa có xe nào đã chụp ảnh"
            }
        } else {
            tvEmpty.visibility = View.GONE
        }
    }

    private fun isVehicleHasPhotos(vehicle: Vehicle): Boolean {
        if (vehicle.photosTaken.isNotEmpty()) return true
        val targetPlate = if (vehicle.plateClean.isNotBlank()) vehicle.plateClean else vehicle.plate
        if (hasLocalPhotos(targetPlate, vehicle.lanKd)) return true
        return false
    }

    private fun getLocalPhotoCount(plate: String, lanKd: Int): Int {
        if (plate.isBlank()) return 0
        val cleanPlate = plate.replace(Regex("[^a-zA-Z0-9]"), "").uppercase()
        val foundTypes = mutableSetOf<String>()

        val dirs = listOf(
            if (lanKd > 1) File(filesDir, "review/$cleanPlate/$lanKd") else File(filesDir, "review/$cleanPlate"),
            File(filesDir, "review/$cleanPlate")
        )
        for (dir in dirs) {
            if (dir.exists() && dir.isDirectory) {
                val files = dir.listFiles { f -> f.extension.equals("jpg", ignoreCase = true) } ?: emptyArray()
                for (f in files) {
                    if (isSameDay(f.lastModified())) {
                        foundTypes.add(f.nameWithoutExtension)
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
                    val metaPlate = meta.plate.replace(Regex("[^a-zA-Z0-9]"), "").uppercase()
                    if (metaPlate == cleanPlate && isSameDay(meta.timestamp)) {
                        foundTypes.add(meta.photoType)
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
        return foundTypes.size
    }

    private fun hasLocalPhotos(plate: String, lanKd: Int): Boolean {
        return getLocalPhotoCount(plate, lanKd) > 0
    }

    private fun isVehicleFinished(vehicle: Vehicle): Boolean {
        if (vehicle.isFinished()) return true
        if (hasLocalCompletedPhotos(vehicle.plateClean, vehicle.lanKd)) return true
        return false
    }

    private fun isSameDay(timeMillis: Long): Boolean {
        if (timeMillis <= 0) return false
        val calNow = Calendar.getInstance()
        val calTarget = Calendar.getInstance().apply { this.timeInMillis = timeMillis }
        return calNow.get(Calendar.YEAR) == calTarget.get(Calendar.YEAR) &&
               calNow.get(Calendar.DAY_OF_YEAR) == calTarget.get(Calendar.DAY_OF_YEAR)
    }

    private fun hasLocalCompletedPhotos(plate: String, lanKd: Int): Boolean {
        if (plate.isBlank()) return false
        val cleanPlate = plate.replace(Regex("[^a-zA-Z0-9]"), "").uppercase()

        // 1. Kiểm tra ảnh trong thư mục review (chỉ tính ảnh chụp trong ngày hôm nay)
        val reviewDir = if (lanKd > 1) File(filesDir, "review/$cleanPlate/$lanKd") else File(filesDir, "review/$cleanPlate")
        var hasLocalRear = false
        var hasLocalFront = false

        if (reviewDir.exists() && reviewDir.isDirectory) {
            val files = reviewDir.listFiles { f -> f.extension.equals("jpg", ignoreCase = true) } ?: emptyArray()
            for (f in files) {
                if (isSameDay(f.lastModified())) {
                    if (f.name.startsWith("rear_45")) hasLocalRear = true
                    if (f.name.startsWith("front_45")) hasLocalFront = true
                }
            }
        }

        // 2. Kiểm tra ảnh trong thư mục pending (ảnh đang chờ upload trong ngày hôm nay)
        if (!hasLocalRear || !hasLocalFront) {
            val pendingDir = File(filesDir, "pending")
            if (pendingDir.exists() && pendingDir.isDirectory) {
                val metaFiles = pendingDir.listFiles { f -> f.extension == "meta" } ?: emptyArray()
                val gson = Gson()
                for (mf in metaFiles) {
                    try {
                        val meta = gson.fromJson(mf.readText(), PendingUploadMetadata::class.java)
                        val metaPlate = meta.plate.replace(Regex("[^a-zA-Z0-9]"), "").uppercase()
                        if (metaPlate == cleanPlate && meta.lanKd == lanKd && isSameDay(meta.timestamp)) {
                            if (meta.photoType == "rear_45") hasLocalRear = true
                            if (meta.photoType == "front_45") hasLocalFront = true
                        }
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }
        }

        return hasLocalRear && hasLocalFront
    }

    /**
     * Kiểm tra phương tiện đã có đủ dữ liệu / đủ ảnh trong ngày hôm nay chưa.
     * Nếu đã có đủ dữ liệu lần 1 thì khi nhập thủ công mặc định chuyển sang Lần 2.
     */
    private fun isPlateDataCompleteToday(basePlate: String, cleaned: String): Boolean {
        if (basePlate.isBlank()) return false
        val cleanBase = basePlate.replace(Regex("[^a-zA-Z0-9]"), "").uppercase()
        val cleanInput = cleaned.replace(Regex("[^a-zA-Z0-9]"), "").uppercase()

        // 1. Tìm trong danh sách xe tiếp nhận / đồng bộ hôm nay
        val matchingList = currentVehiclesList.filter {
            val itClean = it.plateClean.replace(Regex("[^a-zA-Z0-9]"), "").uppercase()
            val itPlate = it.plate.replace(Regex("[^a-zA-Z0-9]"), "").uppercase()
            itClean == cleanBase || itClean == cleanInput || itPlate == cleanBase || itPlate == cleanInput
        }

        for (matching in matchingList) {
            // Đã là lần 2 trở lên hoặc có gợi ý lần 2
            if (matching.lanKd >= 2 || matching.suggestLan2) return true

            // Đã hoàn thành kiểm định
            if (isVehicleFinished(matching)) return true

            // Đã chụp đủ ảnh góc trước và góc sau 45 độ trên server
            val photos = matching.photosTaken
            val hasRear = photos.any { it.startsWith("rear_45") }
            val hasFront = photos.any { it.startsWith("front_45") }
            if (hasRear && hasFront) return true

            // Đã có đủ ảnh Lần 1 lưu cục bộ trên máy hôm nay
            if (hasLocalCompletedPhotos(matching.plateClean, 1)) return true
        }

        // 2. Kiểm tra ảnh lưu cục bộ trên máy (thư mục review hoặc pending) cho Lần 1 trong ngày
        if (hasLocalCompletedPhotos(cleanBase, 1)) return true
        if (hasLocalCompletedPhotos(cleanInput, 1)) return true

        return false
    }

    private fun initViews() {
        btnSettings = findViewById(R.id.btn_settings)
        tvPendingBanner = findViewById(R.id.tv_pending_banner)
        tilPlate = findViewById(R.id.til_plate)
        etPlate = findViewById(R.id.et_plate)
        rgPlateColor = findViewById(R.id.rg_plate_color)
        rgLanKd = findViewById(R.id.rg_lan_kd)
        rbLan1 = findViewById(R.id.rb_lan_1)
        rbLan2 = findViewById(R.id.rb_lan_2)
        btnSelectManual = findViewById(R.id.btn_select_manual)

        layoutVehicleListContainer = findViewById(R.id.layout_vehicle_list_container)
        btnFilterWaiting = findViewById(R.id.btn_filter_waiting)
        btnFilterAll = findViewById(R.id.btn_filter_all)
        swipeRefresh = findViewById(R.id.swipe_refresh)
        rvVehicles = findViewById(R.id.rv_vehicles)
        pbLoading = findViewById(R.id.pb_loading)
        tvEmpty = findViewById(R.id.tv_empty)
    }

    private fun setupRecyclerView() {
        vehicleAdapter = VehicleAdapter { vehicle ->
            val lan = if (vehicle.lanKd >= 2 || vehicle.suggestLan2 || isVehicleFinished(vehicle)) 2 else 1
            openCamera(vehicle.plateClean, vehicle.plateColor, vehicle.photosTaken, lanKd = lan)
        }
        rvVehicles.layoutManager = LinearLayoutManager(this)
        rvVehicles.adapter = vehicleAdapter
    }

    private fun setupListeners() {
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnSelectManual.setOnClickListener {
            handleManualSelect()
        }

        btnFilterWaiting.setOnClickListener {
            if (!isFilterWaiting) {
                isFilterWaiting = true
                updateFilterButtons()
                if (prefs.firebaseEnabled) {
                    applyCurrentFilter()
                } else {
                    loadVehicles()
                }
                rvVehicles.scrollToPosition(0)
            }
        }

        btnFilterAll.setOnClickListener {
            if (isFilterWaiting) {
                isFilterWaiting = false
                updateFilterButtons()
                if (prefs.firebaseEnabled) {
                    applyCurrentFilter()
                } else {
                    loadVehicles()
                }
                rvVehicles.scrollToPosition(0)
            }
        }

        etPlate.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val raw = s?.toString()?.trim().orEmpty()
                val cleaned = raw.replace(Regex("[.\\-\\s]"), "").uppercase()
                if (cleaned.isNotEmpty()) {
                    val (basePlate, color) = PlateUtil.extractColor(cleaned)
                    val isOld = PlateUtil.isOldPlate(basePlate)
                    if (!isOld) {
                        val matchedVehicle = currentVehiclesList.firstOrNull {
                            val itClean = it.plateClean.replace(Regex("[^a-zA-Z0-9]"), "").uppercase()
                            val itPlate = it.plate.replace(Regex("[^a-zA-Z0-9]"), "").uppercase()
                            itClean == basePlate || itClean == cleaned || itPlate == basePlate || itPlate == cleaned
                        }
                        val finalPlateColor = color ?: matchedVehicle?.plateColor
                        when (finalPlateColor) {
                            "T" -> rgPlateColor.check(R.id.rb_color_white)
                            "V" -> rgPlateColor.check(R.id.rb_color_yellow)
                            "X" -> rgPlateColor.check(R.id.rb_color_blue)
                            else -> {
                                if (rgPlateColor.checkedRadioButtonId == -1) {
                                    rgPlateColor.check(R.id.rb_color_white)
                                }
                            }
                        }
                    }

                    // Tự động nhận diện Lần 2 khi dữ liệu trong ngày đã có đủ
                    if (isPlateDataCompleteToday(basePlate, cleaned)) {
                        rgLanKd.check(R.id.rb_lan_2)
                    } else {
                        rgLanKd.check(R.id.rb_lan_1)
                    }
                } else {
                    rgLanKd.check(R.id.rb_lan_1)
                }
            }
        })

        swipeRefresh.setOnRefreshListener {
            observeFirebaseVehicles()
        }

        tvPendingBanner.setOnClickListener {
            // Trigger offline worker immediately
            val oneTimeRequest = androidx.work.OneTimeWorkRequestBuilder<PendingUploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(this).enqueue(oneTimeRequest)
            Toast.makeText(this, "Đang gửi ảnh chờ...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleManualSelect() {
        tilPlate.error = null
        val rawInput = etPlate.text?.toString()?.trim().orEmpty()
        if (rawInput.isEmpty()) {
            tilPlate.error = getString(R.string.error_invalid_plate)
            return
        }

        val cleanPlate = try {
            PlateUtil.normalize(rawInput)
        } catch (e: Exception) {
            tilPlate.error = getString(R.string.error_invalid_plate)
            return
        }

        val parsed = PlateUtil.parsePlate(cleanPlate)
        val basePlate = parsed.basePlate
        val isOld = PlateUtil.isOldPlate(basePlate)

        val finalColor = if (isOld) {
            null // Biển cũ mặc định không thêm t/v/x
        } else {
            val selectedColor = when (rgPlateColor.checkedRadioButtonId) {
                R.id.rb_color_white -> "T"
                R.id.rb_color_yellow -> "V"
                R.id.rb_color_blue -> "X"
                else -> "T"
            }
            parsed.color ?: selectedColor
        }

        val selectedLan = if (rbLan2.isChecked) {
            2
        } else if (isPlateDataCompleteToday(basePlate, cleanPlate)) {
            rgLanKd.check(R.id.rb_lan_2)
            2
        } else {
            1
        }
        val finalLan = selectedLan

        openCamera(basePlate, finalColor, null, lanKd = finalLan)
    }

    private fun updateFilterButtons() {
        if (isFilterWaiting) {
            btnFilterWaiting.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
            btnFilterWaiting.setTextColor(ContextCompat.getColor(this, R.color.text_white))
            btnFilterAll.setBackgroundColor(ContextCompat.getColor(this, android.R.color.transparent))
            btnFilterAll.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnFilterAll.strokeWidth = 2
            btnFilterAll.setStrokeColorResource(R.color.divider)
        } else {
            btnFilterAll.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
            btnFilterAll.setTextColor(ContextCompat.getColor(this, R.color.text_white))
            btnFilterWaiting.setBackgroundColor(ContextCompat.getColor(this, android.R.color.transparent))
            btnFilterWaiting.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnFilterWaiting.strokeWidth = 2
            btnFilterWaiting.setStrokeColorResource(R.color.divider)
        }
    }

    private fun openCamera(plate: String, plateColor: String?, photosTaken: List<String>? = null, lanKd: Int = 1) {
        val parsed = PlateUtil.parsePlate(plate)
        val basePlate = parsed.basePlate
        val isOld = PlateUtil.isOldPlate(basePlate)
        var color = if (isOld) {
            null // Biển cũ mặc định không thêm t/v/x
        } else {
            plateColor ?: parsed.color
        }
        if (!isOld && color == null && Regex("\\d{5}$").containsMatchIn(basePlate)) {
            color = "T"
        }
        val effectiveLan = if (lanKd > 1) lanKd else parsed.lanKd

        val intent = Intent(this, CameraActivity::class.java).apply {
            putExtra(CameraActivity.EXTRA_PLATE, basePlate)
            putExtra(CameraActivity.EXTRA_PLATE_COLOR, color)
            putExtra(CameraActivity.EXTRA_LAN_KD, effectiveLan)
            if (photosTaken != null) {
                putStringArrayListExtra(CameraActivity.EXTRA_PHOTOS_TAKEN, ArrayList(photosTaken))
            }
        }
        startActivity(intent)
    }

    private fun applyVehicleListVisibility() {
        if (prefs.vehicleListEnabled) {
            layoutVehicleListContainer.visibility = View.VISIBLE
            stopPeriodicRefresh()
            observeFirebaseVehicles()
            if (currentVehiclesList.isNotEmpty()) {
                applyCurrentFilter()
            }
        } else {
            layoutVehicleListContainer.visibility = View.GONE
            stopPeriodicRefresh()
            firebaseJob?.cancel()
            firebaseJob = null
        }
    }

    private fun loadVehicles(silent: Boolean = false) {
        if (!prefs.vehicleListEnabled) return
        observeFirebaseVehicles()
    }

    private fun updatePendingBanner() {
        val pendingDir = File(filesDir, "pending")
        val count = pendingDir.listFiles { f -> f.extension.equals("jpg", ignoreCase = true) }?.size ?: 0
        if (count > 0) {
            tvPendingBanner.visibility = View.VISIBLE
            tvPendingBanner.text = getString(R.string.pending_badge, count)
        } else {
            tvPendingBanner.visibility = View.GONE
        }
    }

    private fun checkPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST_CAMERA
            )
        }
    }

    private fun scheduleOfflineWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicWork = PeriodicWorkRequestBuilder<PendingUploadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "PendingUploadWorker",
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWork
        )
    }
}
