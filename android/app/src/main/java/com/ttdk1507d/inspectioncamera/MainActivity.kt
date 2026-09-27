package com.ttdk1507d.inspectioncamera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
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
import com.ttdk1507d.inspectioncamera.adapter.VehicleAdapter
import com.ttdk1507d.inspectioncamera.api.ApiClient
import com.ttdk1507d.inspectioncamera.model.Vehicle
import com.ttdk1507d.inspectioncamera.util.NetworkUtil
import com.ttdk1507d.inspectioncamera.util.PlateUtil
import com.ttdk1507d.inspectioncamera.util.PrefsManager
import com.ttdk1507d.inspectioncamera.worker.PendingUploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CAMERA = 1001
    }

    private lateinit var prefs: PrefsManager
    private lateinit var vehicleAdapter: VehicleAdapter

    private lateinit var btnSettings: ImageButton
    private lateinit var tvPendingBanner: TextView
    private lateinit var tilPlate: TextInputLayout
    private lateinit var etPlate: TextInputEditText
    private lateinit var rgPlateColor: RadioGroup
    private lateinit var btnSelectManual: MaterialButton

    private lateinit var layoutVehicleListContainer: View
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var rvVehicles: RecyclerView
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvEmpty: TextView

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

    private fun initViews() {
        btnSettings = findViewById(R.id.btn_settings)
        tvPendingBanner = findViewById(R.id.tv_pending_banner)
        tilPlate = findViewById(R.id.til_plate)
        etPlate = findViewById(R.id.et_plate)
        rgPlateColor = findViewById(R.id.rg_plate_color)
        btnSelectManual = findViewById(R.id.btn_select_manual)

        layoutVehicleListContainer = findViewById(R.id.layout_vehicle_list_container)
        swipeRefresh = findViewById(R.id.swipe_refresh)
        rvVehicles = findViewById(R.id.rv_vehicles)
        pbLoading = findViewById(R.id.pb_loading)
        tvEmpty = findViewById(R.id.tv_empty)
    }

    private fun setupRecyclerView() {
        vehicleAdapter = VehicleAdapter { vehicle ->
            openCamera(vehicle.plateClean, vehicle.plateColor, vehicle.photosTaken)
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

        etPlate.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val raw = s?.toString()?.trim().orEmpty()
                val cleaned = raw.replace(Regex("[.\\-\\s]"), "").uppercase()
                if (cleaned.isNotEmpty()) {
                    val (_, color) = PlateUtil.extractColor(cleaned)
                    when (color) {
                        "T" -> rgPlateColor.check(R.id.rb_color_white)
                        "V" -> rgPlateColor.check(R.id.rb_color_yellow)
                        "X" -> rgPlateColor.check(R.id.rb_color_blue)
                        else -> {
                            if (cleaned.last().isDigit() && Regex("^[0-9]{2}[A-Z]{1,2}[0-9]{4}$").matches(cleaned)) {
                                rgPlateColor.check(R.id.rb_color_none)
                            }
                        }
                    }
                }
            }
        })

        swipeRefresh.setOnRefreshListener {
            loadVehicles()
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

        val (basePlate, extractedColor) = PlateUtil.extractColor(cleanPlate)

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
            rgPlateColor.checkedRadioButtonId == R.id.rb_color_none -> null
            rgPlateColor.checkedRadioButtonId == -1 -> null
            selectedColor != null -> selectedColor
            cleanPlate.last().isDigit() -> null
            else -> null
        }

        val finalPlate = if (extractedColor != null) basePlate else cleanPlate

        openCamera(finalPlate, finalColor, null)
    }

    private fun openCamera(plate: String, plateColor: String?, photosTaken: List<String>? = null) {
        val intent = Intent(this, CameraActivity::class.java).apply {
            putExtra(CameraActivity.EXTRA_PLATE, plate)
            putExtra(CameraActivity.EXTRA_PLATE_COLOR, plateColor)
            if (photosTaken != null) {
                putStringArrayListExtra(CameraActivity.EXTRA_PHOTOS_TAKEN, ArrayList(photosTaken))
            }
        }
        startActivity(intent)
    }

    private fun applyVehicleListVisibility() {
        if (prefs.vehicleListEnabled) {
            layoutVehicleListContainer.visibility = View.VISIBLE
            loadVehicles()
        } else {
            layoutVehicleListContainer.visibility = View.GONE
        }
    }

    private fun loadVehicles() {
        if (!prefs.vehicleListEnabled) return

        lifecycleScope.launch {
            pbLoading.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE

            try {
                val baseUrl = NetworkUtil.resolveBaseUrl(prefs.lanUrl, prefs.tailscaleUrl)
                val service = ApiClient.getService(baseUrl)
                val response = withContext(Dispatchers.IO) {
                    service.getVehiclesToday()
                }

                if (response.isSuccessful && response.body() != null) {
                    val list = response.body()!!
                    vehicleAdapter.updateList(list)
                    if (list.isEmpty()) {
                        tvEmpty.visibility = View.VISIBLE
                    } else {
                        tvEmpty.visibility = View.GONE
                    }
                } else {
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = getString(R.string.empty_vehicle_list)
                }
            } catch (e: Exception) {
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = getString(R.string.empty_vehicle_list)
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
