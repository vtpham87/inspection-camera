package com.ttdk1507d.inspectioncamera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
            }
        }
    }

    private fun applyCurrentFilter() {
        val filtered = if (isFilterWaiting) {
            currentVehiclesList.filter { vehicle ->
                !isVehicleFinished(vehicle)
            }
        } else {
            currentVehiclesList
        }
        vehicleAdapter.updateList(filtered)
        if (filtered.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = if (isFilterWaiting) {
                "Không có xe nào đang chờ\n(Đã hoàn thành kiểm định hoặc chụp ảnh xong)"
            } else {
                "Hôm nay chưa có xe kiểm định nào"
            }
        } else {
            tvEmpty.visibility = View.GONE
        }
    }

    private fun isVehicleFinished(vehicle: Vehicle): Boolean {
        if (vehicle.isFinished()) return true
        if (hasLocalCompletedPhotos(vehicle.plateClean, vehicle.lanKd)) return true
        return false
    }

    private fun hasLocalCompletedPhotos(plate: String, lanKd: Int): Boolean {
        if (plate.isBlank()) return false
        val cleanPlate = plate.replace(Regex("[^a-zA-Z0-9]"), "").uppercase()

        // 1. Kiểm tra ảnh trong thư mục review
        val reviewDir = if (lanKd > 1) File(filesDir, "review/$cleanPlate/$lanKd") else File(filesDir, "review/$cleanPlate")
        var hasLocalRear = false
        var hasLocalFront = false

        if (reviewDir.exists() && reviewDir.isDirectory) {
            val files = reviewDir.listFiles { f -> f.extension.equals("jpg", ignoreCase = true) } ?: emptyArray()
            for (f in files) {
                if (f.name.startsWith("rear_45")) hasLocalRear = true
                if (f.name.startsWith("front_45")) hasLocalFront = true
            }
        }

        // 2. Kiểm tra ảnh trong thư mục pending (ảnh đang chờ upload)
        if (!hasLocalRear || !hasLocalFront) {
            val pendingDir = File(filesDir, "pending")
            if (pendingDir.exists() && pendingDir.isDirectory) {
                val metaFiles = pendingDir.listFiles { f -> f.extension == "meta" } ?: emptyArray()
                val gson = Gson()
                for (mf in metaFiles) {
                    try {
                        val meta = gson.fromJson(mf.readText(), PendingUploadMetadata::class.java)
                        val metaPlate = meta.plate.replace(Regex("[^a-zA-Z0-9]"), "").uppercase()
                        if (metaPlate == cleanPlate && meta.lanKd == lanKd) {
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
            val lan = if (vehicle.lanKd >= 2 || vehicle.suggestLan2) 2 else 1
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
                    when (color) {
                        "T" -> rgPlateColor.check(R.id.rb_color_white)
                        "V" -> rgPlateColor.check(R.id.rb_color_yellow)
                        "X" -> rgPlateColor.check(R.id.rb_color_blue)
                        else -> {
                            if (cleaned.last().isDigit() && Regex("^[0-9]{2}[A-Z]{1,2}[0-9]{4}$").matches(cleaned)) {
                                rgPlateColor.check(R.id.rb_color_none)
                            } else if (cleaned.isNotEmpty() && Regex("\\d{5}$").containsMatchIn(cleaned)) {
                                if (rgPlateColor.checkedRadioButtonId == R.id.rb_color_none || rgPlateColor.checkedRadioButtonId == -1) {
                                    rgPlateColor.check(R.id.rb_color_white)
                                }
                            }
                        }
                    }

                    // Tự động nhận diện Lần 2 từ danh sách xe đã quét trong ngày
                    val matching = currentVehiclesList.firstOrNull {
                        it.plateClean.equals(cleaned, ignoreCase = true) ||
                        it.plateClean.equals(basePlate, ignoreCase = true) ||
                        it.plate.replace(Regex("[.\\-\\s]"), "").equals(cleaned, ignoreCase = true)
                    }
                    if (matching != null) {
                        if (matching.lanKd >= 2 || matching.suggestLan2) {
                            rgLanKd.check(R.id.rb_lan_2)
                        } else {
                            rgLanKd.check(R.id.rb_lan_1)
                        }
                    }
                }
            }
        })

        swipeRefresh.setOnRefreshListener {
            if (prefs.firebaseEnabled) {
                observeFirebaseVehicles()
            } else {
                loadVehicles()
            }
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
        val extractedColor = parsed.color

        val selectedColor = when (rgPlateColor.checkedRadioButtonId) {
            R.id.rb_color_white -> "T"
            R.id.rb_color_yellow -> "V"
            R.id.rb_color_blue -> "X"
            R.id.rb_color_none -> null
            else -> null
        }

        // Determine final plate and color:
        // 1. If plate ends with color suffix (T/V/X), use extracted color.
        // 2. If user selected rb_color_none or didn't specify, plateColor is null.
        // 3. If entered plate ends with a digit (old plate like 11K2639), do NOT force "T".
        val finalColor = when {
            extractedColor != null -> extractedColor
            selectedColor != null -> selectedColor
            rgPlateColor.checkedRadioButtonId == R.id.rb_color_none -> null
            basePlate.isNotEmpty() && Regex("\\d{5}$").containsMatchIn(basePlate) -> "T"
            else -> null
        }

        val selectedLan = if (rbLan2.isChecked) 2 else 1
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
        var color = plateColor ?: parsed.color
        if (color == null && Regex("\\d{5}$").containsMatchIn(basePlate)) {
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
            if (prefs.firebaseEnabled) {
                stopPeriodicRefresh()
                observeFirebaseVehicles()
                if (currentVehiclesList.isNotEmpty()) {
                    applyCurrentFilter()
                }
            } else {
                firebaseJob?.cancel()
                firebaseJob = null
                loadVehicles(silent = currentVehiclesList.isNotEmpty())
                startPeriodicRefresh()
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

        lifecycleScope.launch {
            if (!silent && currentVehiclesList.isEmpty()) {
                pbLoading.visibility = View.VISIBLE
                tvEmpty.visibility = View.GONE
            }

            try {
                val baseUrl = NetworkUtil.resolveBaseUrl(prefs)
                if (baseUrl == null) {
                    if (prefs.firebaseEnabled) {
                        observeFirebaseVehicles()
                        return@launch
                    }
                    if (!silent) {
                        if (currentVehiclesList.isEmpty()) {
                            tvEmpty.visibility = View.VISIBLE
                            tvEmpty.text = when {
                                !prefs.lanEnabled && !prefs.tailscaleEnabled ->
                                    "Cả mạng LAN và Tailscale đều đang tắt\nVào Cài đặt để bật lại kết nối"
                                prefs.lanEnabled && !prefs.tailscaleEnabled ->
                                    "Không kết nối được máy chủ LAN (${prefs.lanIp}:${prefs.serverPort})\nVuốt xuống để thử lại"
                                !prefs.lanEnabled && prefs.tailscaleEnabled ->
                                    "Không kết nối được máy chủ Tailscale (${prefs.tailscaleIp}:${prefs.serverPort})\nVuốt xuống để thử lại"
                                else ->
                                    "Không kết nối được máy chủ\n(LAN: ${prefs.lanIp} • Tailscale: ${prefs.tailscaleIp})\nVuốt xuống để thử lại"
                            }
                        } else {
                            Toast.makeText(this@MainActivity, "Không thể kết nối máy chủ, đang dùng danh sách hiện tại", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@launch
                }
                val service = ApiClient.getService(baseUrl)
                val response = withContext(Dispatchers.IO) {
                    service.getVehiclesToday(waitingOnly = isFilterWaiting)
                }

                if (response.isSuccessful && response.body() != null) {
                    val list = response.body()!!
                    currentVehiclesList = list
                    applyCurrentFilter()
                } else if (!silent) {
                    if (currentVehiclesList.isEmpty()) {
                        tvEmpty.visibility = View.VISIBLE
                        tvEmpty.text = getString(R.string.empty_vehicle_list)
                    } else {
                        Toast.makeText(this@MainActivity, "Lỗi tải danh sách xe từ máy chủ", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                if (!silent) {
                    if (currentVehiclesList.isEmpty()) {
                        tvEmpty.visibility = View.VISIBLE
                        tvEmpty.text = "Lỗi kết nối: ${e.message}\nVuốt xuống để thử lại"
                    } else {
                        Toast.makeText(this@MainActivity, "Lỗi kết nối: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                pbLoading.visibility = View.GONE
                swipeRefresh.isRefreshing = false
            }
        }
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
