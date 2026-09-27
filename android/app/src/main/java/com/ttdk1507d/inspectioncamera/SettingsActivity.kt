package com.ttdk1507d.inspectioncamera

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.ttdk1507d.inspectioncamera.api.ApiClient
import com.ttdk1507d.inspectioncamera.util.NetworkUtil
import com.ttdk1507d.inspectioncamera.util.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager

    private lateinit var toolbar: MaterialToolbar
    private lateinit var etLanIp: TextInputEditText
    private lateinit var etTailscaleIp: TextInputEditText
    private lateinit var etPort: TextInputEditText
    private lateinit var etPhotoSaveDir: TextInputEditText
    private lateinit var switchVehicleList: MaterialSwitch
    private lateinit var btnTest: MaterialButton
    private lateinit var tvTestResult: TextView

    // Timestamp
    private lateinit var switchTimestamp: MaterialSwitch
    private lateinit var spinnerTimestampFormat: Spinner
    private lateinit var spinnerTimestampPosition: Spinner
    private lateinit var spinnerTimestampFontSize: Spinner
    private lateinit var switchTimestampStroke: MaterialSwitch

    // Photo & Resolution
    private lateinit var spinnerResolution: Spinner
    private lateinit var spinnerJpegQuality: Spinner
    private lateinit var switchPlateColorSuffix: MaterialSwitch
    private lateinit var spinnerUploadMode: Spinner

    private lateinit var btnSave: MaterialButton

    private val formatKeys = listOf(
        "HH:mm:ss - dd/MM/yyyy",
        "dd/MM/yyyy HH:mm:ss",
        "HH:mm dd/MM/yyyy",
        "yyyy-MM-dd HH:mm:ss"
    )

    private val positionKeys = listOf(
        "bottom_right",
        "bottom_left",
        "top_right",
        "top_left"
    )

    private val fontSizes = listOf(22, 28, 34, 40)

    private val resolutionKeys = listOf(
        "original",
        "high",
        "medium",
        "low"
    )

    private val jpegQualities = listOf(75, 85, 90, 95)

    private val uploadModeKeys = listOf("review", "immediate")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = PrefsManager(this)

        initViews()
        setupSpinners()
        loadCurrentSettings()
        setupListeners()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar_settings)
        etLanIp = findViewById(R.id.et_settings_lan_ip)
        etTailscaleIp = findViewById(R.id.et_settings_tailscale_ip)
        etPort = findViewById(R.id.et_settings_port)
        etPhotoSaveDir = findViewById(R.id.et_settings_photo_save_dir)
        switchVehicleList = findViewById(R.id.switch_settings_vehicle_list)
        btnTest = findViewById(R.id.btn_settings_test)
        tvTestResult = findViewById(R.id.tv_settings_test_result)

        switchTimestamp = findViewById(R.id.switch_settings_timestamp)
        spinnerTimestampFormat = findViewById(R.id.spinner_settings_timestamp_format)
        spinnerTimestampPosition = findViewById(R.id.spinner_settings_timestamp_position)
        spinnerTimestampFontSize = findViewById(R.id.spinner_settings_timestamp_font_size)
        switchTimestampStroke = findViewById(R.id.switch_settings_timestamp_stroke)

        spinnerResolution = findViewById(R.id.spinner_settings_resolution)
        spinnerJpegQuality = findViewById(R.id.spinner_settings_jpeg_quality)
        switchPlateColorSuffix = findViewById(R.id.switch_settings_plate_color_suffix)
        spinnerUploadMode = findViewById(R.id.spinner_settings_upload_mode)

        btnSave = findViewById(R.id.btn_settings_save)

        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupSpinners() {
        val formatLabels = listOf(
            "HH:mm:ss - dd/MM/yyyy (Mặc định)",
            "dd/MM/yyyy HH:mm:ss",
            "HH:mm dd/MM/yyyy",
            "yyyy-MM-dd HH:mm:ss"
        )
        spinnerTimestampFormat.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, formatLabels
        )

        val positionLabels = listOf(
            "Góc dưới bên phải (Khuyên dùng)",
            "Góc dưới bên trái",
            "Góc trên bên phải",
            "Góc trên bên trái"
        )
        spinnerTimestampPosition.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, positionLabels
        )

        val fontSizeLabels = listOf(
            "Nhỏ (22sp)",
            "Tiêu chuẩn (28sp - Khuyên dùng)",
            "Lớn (34sp)",
            "Rất lớn (40sp)"
        )
        spinnerTimestampFontSize.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, fontSizeLabels
        )

        val resolutionLabels = listOf(
            "Gốc camera (original - Tối đa)",
            "4K (high - 3840×2160)",
            "Full HD (medium - 1920×1080 - Khuyên dùng)",
            "HD (low - 1280×720 - Nhẹ nhất)"
        )
        spinnerResolution.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, resolutionLabels
        )

        val qualityLabels = listOf(
            "75% (Dung lượng rất nhẹ)",
            "85% (Tiêu chuẩn - Khuyên dùng)",
            "90% (Chất lượng cao)",
            "95% (Rất nét)"
        )
        spinnerJpegQuality.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, qualityLabels
        )

        val uploadModeLabels = listOf(
            "Chỉ tải khi mở Xem lại (Khuyên dùng - Nhanh nhất)",
            "Tự động tải ngầm ngay khi chụp"
        )
        spinnerUploadMode.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, uploadModeLabels
        )
    }

    private fun loadCurrentSettings() {
        etLanIp.setText(prefs.lanIp)
        etTailscaleIp.setText(prefs.tailscaleIp)
        etPort.setText(prefs.serverPort.toString())
        etPhotoSaveDir.setText(prefs.photoSaveDir)
        switchVehicleList.isChecked = prefs.vehicleListEnabled

        switchTimestamp.isChecked = prefs.timestampEnabled

        val fmtIdx = formatKeys.indexOf(prefs.timestampFormat)
        spinnerTimestampFormat.setSelection(if (fmtIdx >= 0) fmtIdx else 0)

        val posIdx = positionKeys.indexOf(prefs.timestampPosition)
        spinnerTimestampPosition.setSelection(if (posIdx >= 0) posIdx else 0)

        val fontIdx = fontSizes.indexOf(prefs.timestampFontSize)
        spinnerTimestampFontSize.setSelection(if (fontIdx >= 0) fontIdx else 1)

        switchTimestampStroke.isChecked = prefs.timestampStrokeEnabled

        val resIdx = resolutionKeys.indexOf(prefs.photoResolution)
        spinnerResolution.setSelection(if (resIdx >= 0) resIdx else 0)

        val qualIdx = jpegQualities.indexOf(prefs.jpegQuality)
        spinnerJpegQuality.setSelection(if (qualIdx >= 0) qualIdx else 1)

        switchPlateColorSuffix.isChecked = prefs.plateColorSuffix

        val upIdx = uploadModeKeys.indexOf(prefs.uploadMode)
        spinnerUploadMode.setSelection(if (upIdx >= 0) upIdx else 0)
    }

    private fun setupListeners() {
        btnTest.setOnClickListener {
            testConnections()
        }

        btnSave.setOnClickListener {
            saveSettings()
        }
    }

    private fun testConnections() {
        val lanIp = etLanIp.text?.toString()?.trim().orEmpty()
        val tailscaleIp = etTailscaleIp.text?.toString()?.trim().orEmpty()
        val port = etPort.text?.toString()?.trim()?.toIntOrNull() ?: prefs.serverPort

        val lanUrl = "http://$lanIp:$port"
        val tailscaleUrl = "http://$tailscaleIp:$port"

        btnTest.isEnabled = false
        tvTestResult.visibility = View.VISIBLE
        tvTestResult.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        tvTestResult.text = getString(R.string.test_testing)

        lifecycleScope.launch {
            val lanDeferred = async(Dispatchers.IO) { checkHealth(lanUrl) }
            val tsDeferred = async(Dispatchers.IO) { checkHealth(tailscaleUrl) }

            val lanOk = lanDeferred.await()
            val tsOk = tsDeferred.await()

            btnTest.isEnabled = true

            val resultText = buildString {
                append("LAN ($lanIp:$port): ")
                append(if (lanOk) "✅ Hoạt động" else "❌ Không phản hồi")
                append("\nTailscale ($tailscaleIp:$port): ")
                append(if (tsOk) "✅ Hoạt động" else "❌ Không phản hồi")
            }

            tvTestResult.text = resultText
            val resultColor = if (lanOk || tsOk) {
                R.color.status_done_text
            } else {
                R.color.error
            }
            tvTestResult.setTextColor(ContextCompat.getColor(this@SettingsActivity, resultColor))
        }
    }

    private suspend fun checkHealth(url: String): Boolean {
        return try {
            val service = ApiClient.getService(url)
            val resp = service.health()
            resp.isSuccessful && resp.body()?.get("ok") == true
        } catch (e: Exception) {
            false
        }
    }

    private fun saveSettings() {
        val lanIp = etLanIp.text?.toString()?.trim().orEmpty()
        val tailscaleIp = etTailscaleIp.text?.toString()?.trim().orEmpty()
        val port = etPort.text?.toString()?.trim()?.toIntOrNull()
        val saveDir = etPhotoSaveDir.text?.toString()?.trim().orEmpty()

        if (lanIp.isEmpty()) {
            etLanIp.error = "IP LAN không được để trống"
            return
        }

        if (tailscaleIp.isEmpty()) {
            etTailscaleIp.error = "IP Tailscale không được để trống"
            return
        }

        if (port == null || port !in 1..65535) {
            etPort.error = "Port phải từ 1 đến 65535"
            return
        }

        if (saveDir.isEmpty()) {
            etPhotoSaveDir.error = "Đường dẫn lưu ảnh không được để trống"
            return
        }

        // Save immediately to local persistent preferences
        prefs.lanIp = lanIp
        prefs.tailscaleIp = tailscaleIp
        prefs.serverPort = port
        prefs.photoSaveDir = saveDir
        prefs.vehicleListEnabled = switchVehicleList.isChecked

        prefs.timestampEnabled = switchTimestamp.isChecked
        prefs.timestampFormat = formatKeys[spinnerTimestampFormat.selectedItemPosition.coerceIn(0, formatKeys.lastIndex)]
        prefs.timestampPosition = positionKeys[spinnerTimestampPosition.selectedItemPosition.coerceIn(0, positionKeys.lastIndex)]
        prefs.timestampFontSize = fontSizes[spinnerTimestampFontSize.selectedItemPosition.coerceIn(0, fontSizes.lastIndex)]
        prefs.timestampStrokeEnabled = switchTimestampStroke.isChecked

        prefs.photoResolution = resolutionKeys[spinnerResolution.selectedItemPosition.coerceIn(0, resolutionKeys.lastIndex)]
        prefs.jpegQuality = jpegQualities[spinnerJpegQuality.selectedItemPosition.coerceIn(0, jpegQualities.lastIndex)]
        prefs.plateColorSuffix = switchPlateColorSuffix.isChecked
        prefs.uploadMode = uploadModeKeys[spinnerUploadMode.selectedItemPosition.coerceIn(0, uploadModeKeys.lastIndex)]

        // Sync with server using independent scope so it outlives this activity's finish()
        val currentConfig = prefs.getAppConfig()
        val lanUrl = prefs.lanUrl
        val tailscaleUrl = prefs.tailscaleUrl
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val baseUrl = NetworkUtil.resolveBaseUrl(lanUrl, tailscaleUrl)
                if (baseUrl != null) {
                    val service = ApiClient.getService(baseUrl)
                    service.saveConfig(currentConfig)
                }
            } catch (e: Exception) {
                // Config already saved locally
            }
        }

        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        finish()
    }
}
