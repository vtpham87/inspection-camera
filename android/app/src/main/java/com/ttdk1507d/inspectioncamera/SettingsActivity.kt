package com.ttdk1507d.inspectioncamera

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.ttdk1507d.inspectioncamera.api.ApiClient
import com.ttdk1507d.inspectioncamera.firebase.FirebaseManager
import com.ttdk1507d.inspectioncamera.util.NetworkUtil
import com.ttdk1507d.inspectioncamera.util.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvCurrentCluster: TextView
    private lateinit var tvPathsSyncStatus: TextView
    private lateinit var etPhotoSaveDir: TextInputEditText
    private lateinit var etPassengerPath: TextInputEditText
    private lateinit var etNewVehiclePath: TextInputEditText
    private lateinit var btnRefreshPcConfig: MaterialButton
    private lateinit var switchPcAutostart: MaterialSwitch
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
    private lateinit var spinnerUploadMode: Spinner

    // Backup & Restore
    private lateinit var btnBackup: MaterialButton
    private lateinit var btnRestore: MaterialButton

    private lateinit var btnSave: MaterialButton

    // Version & GitHub Update
    private lateinit var tvCurrentVersion: TextView
    private lateinit var tvUpdateStatus: TextView
    private lateinit var pbUpdate: ProgressBar
    private lateinit var btnCheckUpdate: MaterialButton
    private lateinit var btnInstallNow: MaterialButton

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { saveBackupToUri(it) }
    }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { confirmAndRestoreFromUri(it) }
    }

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
        "low",
        "medium",
        "high",
        "original"
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
        tvCurrentCluster = findViewById(R.id.tv_settings_current_cluster)
        tvPathsSyncStatus = findViewById(R.id.tv_settings_paths_sync_status)
        etPhotoSaveDir = findViewById(R.id.et_settings_photo_save_dir)
        etPassengerPath = findViewById(R.id.et_settings_passenger_path)
        etNewVehiclePath = findViewById(R.id.et_settings_new_vehicle_path)
        btnRefreshPcConfig = findViewById(R.id.btn_settings_refresh_pc_config)
        switchPcAutostart = findViewById(R.id.switch_settings_pc_autostart)
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
        spinnerUploadMode = findViewById(R.id.spinner_settings_upload_mode)

        btnBackup = findViewById(R.id.btn_settings_backup)
        btnRestore = findViewById(R.id.btn_settings_restore)

        btnSave = findViewById(R.id.btn_settings_save)

        tvCurrentVersion = findViewById(R.id.tv_settings_current_version)
        tvUpdateStatus = findViewById(R.id.tv_settings_update_status)
        pbUpdate = findViewById(R.id.pb_settings_update)
        btnCheckUpdate = findViewById(R.id.btn_settings_check_update)
        btnInstallNow = findViewById(R.id.btn_settings_install_now)

        val destDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: cacheDir
        val existingApk = destDir.listFiles { file -> file.name.startsWith("1507DCamera_") && file.name.endsWith(".apk") }?.maxByOrNull { it.lastModified() }
        if (existingApk != null && existingApk.length() > 1_000_000) {
            btnInstallNow.visibility = View.VISIBLE
            btnInstallNow.text = "CÀI ĐẶT BẢN ĐÃ TẢI (${existingApk.name})"
            btnInstallNow.setOnClickListener {
                installApk(existingApk)
            }
        }

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: BuildConfig.VERSION_NAME
        } catch (e: Exception) {
            BuildConfig.VERSION_NAME
        }
        tvCurrentVersion.text = "Phiên bản hiện tại: v$versionName"

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
            "HD (1280×720 - Chuẩn Đăng kiểm - Khuyên dùng)",
            "Full HD (1920×1080)",
            "4K (3840×2160)",
            "Gốc camera (original - Tối đa)"
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
            "Chỉ tải khi ấn Hoàn thành (Khuyên dùng)",
            "Tự động tải ngầm ngay khi chụp"
        )
        spinnerUploadMode.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, uploadModeLabels
        )
    }

    private fun loadCurrentSettings() {
        val current = com.ttdk1507d.inspectioncamera.firebase.FirebaseManager.getScheduledNode()
        tvCurrentCluster.text = "Cụm hoạt động hôm nay: ${current.name} (${current.scheduleDesc})"

        etPhotoSaveDir.setText(prefs.photoSaveDir)
        etPassengerPath.setText(prefs.passengerPath)
        etNewVehiclePath.setText(prefs.newVehiclePath)
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

        val upIdx = uploadModeKeys.indexOf(prefs.uploadMode)
        spinnerUploadMode.setSelection(if (upIdx >= 0) upIdx else 0)

        switchPcAutostart.isChecked = prefs.autoStartWithWindows

        lifecycleScope.launch {
            val serverConfig = FirebaseManager.fetchConfigOnce()
            if (serverConfig != null) {
                if (prefs.photoSaveDir.isBlank() && etPhotoSaveDir.text.isNullOrBlank()) {
                    applyFirebaseConfig(serverConfig)
                }
                tvPathsSyncStatus.text = "🟢 Đã đồng bộ với máy tính trạm qua Firebase"
                tvPathsSyncStatus.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.success))
            } else {
                tvPathsSyncStatus.text = "⚪ Cấu hình lưu trữ cục bộ (Chưa kết nối Firebase)"
            }
        }
    }

    private fun applyFirebaseConfig(serverConfig: Map<String, Any?>) {
        val dir = serverConfig["photo_save_dir"] as? String
        val pass = serverConfig["passenger_path"] as? String
        val nv = serverConfig["new_vehicle_path"] as? String
        val autostart = serverConfig["auto_start_with_windows"] as? Boolean

        if (!dir.isNullOrBlank() && !dir.contains("..")) {
            prefs.photoSaveDir = dir
            etPhotoSaveDir.setText(dir)
        }
        if (!pass.isNullOrBlank() && !pass.contains("..")) {
            prefs.passengerPath = pass
            etPassengerPath.setText(pass)
        }
        if (!nv.isNullOrBlank() && !nv.contains("..")) {
            prefs.newVehiclePath = nv
            etNewVehiclePath.setText(nv)
        }
        if (autostart != null) {
            prefs.autoStartWithWindows = autostart
            switchPcAutostart.isChecked = autostart
        }
    }

    private fun setupListeners() {
        btnTest.setOnClickListener {
            testConnections()
        }

        btnBackup.setOnClickListener {
            createDocumentLauncher.launch("1507DCamera_config.json")
        }

        btnRestore.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("application/json", "*/*"))
        }

        btnSave.setOnClickListener {
            saveSettings()
        }

        btnRefreshPcConfig.setOnClickListener {
            tvPathsSyncStatus.text = "🔄 Đang tải cấu hình từ máy tính..."
            tvPathsSyncStatus.setTextColor(ContextCompat.getColor(this, R.color.primary))
            lifecycleScope.launch {
                val serverConfig = FirebaseManager.fetchConfigOnce()
                if (serverConfig != null) {
                    applyFirebaseConfig(serverConfig)
                    tvPathsSyncStatus.text = "🟢 Đã đồng bộ thành công từ máy tính!"
                    tvPathsSyncStatus.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.success))
                    Toast.makeText(this@SettingsActivity, "Đã tải cấu hình mới nhất từ máy tính!", Toast.LENGTH_SHORT).show()
                } else {
                    tvPathsSyncStatus.text = "❌ Không kết nối được Firebase"
                    tvPathsSyncStatus.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.accent_red))
                    Toast.makeText(this@SettingsActivity, "Không thể tải cấu hình từ máy tính", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnCheckUpdate.setOnClickListener {
            checkForGitHubUpdates()
        }
    }

    private fun testConnections() {
        btnTest.isEnabled = false
        tvTestResult.visibility = View.VISIBLE
        tvTestResult.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        tvTestResult.text = "Đang kiểm tra kết nối tới 4 cụm máy chủ Firebase..."

        lifecycleScope.launch {
            val results = com.ttdk1507d.inspectioncamera.firebase.FirebaseManager.testAllNodes()
            btnTest.isEnabled = true

            val sb = StringBuilder()
            var allOk = true
            for (r in results) {
                if (r.isOk) {
                    sb.append("• ${r.node.name} (${r.node.scheduleDesc}): ✓ Sẵn sàng (${r.latencyMs}ms)\n")
                } else {
                    allOk = false
                    sb.append("• ${r.node.name} (${r.node.scheduleDesc}): ✗ Lỗi (${r.error})\n")
                }
            }

            tvTestResult.text = sb.toString().trimEnd()
            val resultColor = if (allOk) R.color.status_done_text else R.color.warning
            tvTestResult.setTextColor(ContextCompat.getColor(this@SettingsActivity, resultColor))
        }
    }

    private fun saveSettings() {
        val saveDir = etPhotoSaveDir.text?.toString()?.trim().orEmpty()
        val passengerPath = etPassengerPath.text?.toString()?.trim().orEmpty()
        val newVehiclePath = etNewVehiclePath.text?.toString()?.trim().orEmpty()

        if (saveDir.isEmpty()) {
            etPhotoSaveDir.error = "Đường dẫn lưu ảnh không được để trống"
            return
        }

        // Save immediately to local persistent preferences (100% Firebase default)
        prefs.firebaseEnabled = true
        prefs.lanEnabled = false
        prefs.tailscaleEnabled = false
        prefs.photoSaveDir = saveDir
        if (passengerPath.isNotEmpty()) prefs.passengerPath = passengerPath
        if (newVehiclePath.isNotEmpty()) prefs.newVehiclePath = newVehiclePath
        prefs.vehicleListEnabled = switchVehicleList.isChecked

        prefs.timestampEnabled = switchTimestamp.isChecked
        prefs.timestampFormat = formatKeys[spinnerTimestampFormat.selectedItemPosition.coerceIn(0, formatKeys.lastIndex)]
        prefs.timestampPosition = positionKeys[spinnerTimestampPosition.selectedItemPosition.coerceIn(0, positionKeys.lastIndex)]
        prefs.timestampFontSize = fontSizes[spinnerTimestampFontSize.selectedItemPosition.coerceIn(0, fontSizes.lastIndex)]
        prefs.timestampStrokeEnabled = switchTimestampStroke.isChecked

        prefs.photoResolution = resolutionKeys[spinnerResolution.selectedItemPosition.coerceIn(0, resolutionKeys.lastIndex)]
        prefs.jpegQuality = jpegQualities[spinnerJpegQuality.selectedItemPosition.coerceIn(0, jpegQualities.lastIndex)]
        prefs.plateColorSuffix = true
        prefs.uploadMode = uploadModeKeys[spinnerUploadMode.selectedItemPosition.coerceIn(0, uploadModeKeys.lastIndex)]
        prefs.autoStartWithWindows = switchPcAutostart.isChecked

        // Sync with server — send ALL fields including paths so backend updates correctly
        syncConfigToServer()

        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun saveBackupToUri(uri: Uri) {
        try {
            val backupConfig = prefs.exportBackupConfig()
            val gson = GsonBuilder().setPrettyPrinting().create()
            val json = gson.toJson(backupConfig)
            contentResolver.openOutputStream(uri)?.use { os ->
                os.write(json.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, getString(R.string.backup_success), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "${getString(R.string.backup_error_write)}: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmAndRestoreFromUri(uri: Uri) {
        try {
            val json = contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader(Charsets.UTF_8).readText()
            } ?: run {
                Toast.makeText(this, getString(R.string.restore_error_read), Toast.LENGTH_SHORT).show()
                return
            }

            AlertDialog.Builder(this)
                .setTitle(R.string.restore_confirm_title)
                .setMessage(R.string.restore_confirm_msg)
                .setPositiveButton("Khôi phục") { _, _ ->
                    val success = prefs.restoreFromJson(json)
                    if (success) {
                        loadCurrentSettings()
                        syncConfigToServer()
                        Toast.makeText(this, getString(R.string.restore_success), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, getString(R.string.restore_error_invalid), Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Huỷ", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "${getString(R.string.restore_error_read)}: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun syncConfigToServer() {
        val currentConfig = prefs.getAppConfig()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val body = mapOf<String, Any>(
                    "vehicle_list_enabled" to currentConfig.vehicleListEnabled,
                    "server_port" to currentConfig.serverPort,
                    "photo_save_dir" to currentConfig.photoSaveDir,
                    "passenger_path" to currentConfig.passengerPath,
                    "new_vehicle_path" to currentConfig.newVehiclePath,
                    "sync_new_vehicle_45" to currentConfig.syncNewVehicle45,
                    "auto_start_with_windows" to currentConfig.autoStartWithWindows,
                    "jpeg_quality" to currentConfig.jpegQuality,
                    "plate_color_suffix" to true,
                    "photo_resolution" to currentConfig.photoResolution,
                    "timestamp" to mapOf(
                        "enabled" to prefs.timestampEnabled,
                        "format" to prefs.timestampFormat,
                        "position" to prefs.timestampPosition,
                        "font_size" to prefs.timestampFontSize
                    )
                )

                // Đồng bộ hai chiều lên Firebase để máy tính trạm nhận ngay lập tức
                FirebaseManager.sendConfigUpdate(body)

                val baseUrl = NetworkUtil.resolveBaseUrl(prefs)
                if (baseUrl != null) {
                    val service = ApiClient.getService(baseUrl)
                    service.postConfig(body)
                }
            } catch (e: Exception) {
                Log.w("SettingsActivity", "Lỗi đồng bộ cấu hình: ${e.message}")
            }
        }
    }

    private data class GitHubRelease(
        val tag_name: String? = null,
        val name: String? = null,
        val body: String? = null,
        val assets: List<GitHubAsset>? = null
    )

    private data class GitHubAsset(
        val name: String? = null,
        val browser_download_url: String? = null,
        val size: Long = 0
    )

    private fun checkForGitHubUpdates() {
        btnCheckUpdate.isEnabled = false
        pbUpdate.visibility = View.VISIBLE
        tvUpdateStatus.visibility = View.VISIBLE
        tvUpdateStatus.text = "Đang kiểm tra bản phát hành trên GitHub..."
        tvUpdateStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val url = URL("https://api.github.com/repos/vtpham87/inspection-camera/releases/latest")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.setRequestProperty("User-Agent", "1507DCamera-App")
                    conn.requestMethod = "GET"
                    val code = conn.responseCode
                    if (code == 200) {
                        val json = conn.inputStream.bufferedReader().use { it.readText() }
                        Gson().fromJson(json, GitHubRelease::class.java)
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }
            }

            btnCheckUpdate.isEnabled = true
            pbUpdate.visibility = View.GONE

            if (result == null || result.tag_name.isNullOrEmpty()) {
                tvUpdateStatus.text = "Không thể kiểm tra cập nhật (Kiểm tra kết nối Internet)"
                tvUpdateStatus.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.error))
                return@launch
            }

            val remoteTag = result.tag_name.trim()
            val remoteVer = remoteTag.removePrefix("v").trim()
            val localVer = try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: BuildConfig.VERSION_NAME
            } catch (e: Exception) {
                BuildConfig.VERSION_NAME
            }

            val apkAsset = result.assets?.firstOrNull { it.name?.endsWith(".apk", ignoreCase = true) == true }

            if (isNewerVersion(remoteVer, localVer) && apkAsset?.browser_download_url != null) {
                tvUpdateStatus.text = "Đã có bản cập nhật mới: $remoteTag"
                tvUpdateStatus.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.status_done_text))

                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Cập nhật phiên bản mới")
                    .setMessage("Phát hiện phiên bản mới: $remoteTag (Hiện tại: v$localVer)\n\n${result.body ?: ""}\n\nBạn có muốn tải bản cập nhật về máy không?")
                    .setPositiveButton("Tải về") { _, _ ->
                        downloadApk(apkAsset.browser_download_url, remoteTag)
                    }
                    .setNegativeButton("Để sau", null)
                    .show()
            } else {
                tvUpdateStatus.text = "Bạn đang sử dụng phiên bản mới nhất ($remoteTag)"
                tvUpdateStatus.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.status_done_text))
                Toast.makeText(this@SettingsActivity, "Ứng dụng đang ở phiên bản mới nhất!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        val rParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val lParts = local.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    private fun downloadApk(downloadUrl: String, newTag: String) {
        btnCheckUpdate.isEnabled = false
        pbUpdate.visibility = View.VISIBLE
        tvUpdateStatus.visibility = View.VISIBLE
        tvUpdateStatus.text = "Đang tải xuống $newTag..."
        tvUpdateStatus.setTextColor(ContextCompat.getColor(this, R.color.primary))
        btnInstallNow.visibility = View.GONE

        lifecycleScope.launch {
            val apkFile = withContext(Dispatchers.IO) {
                try {
                    val destDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: cacheDir
                    val targetFile = File(destDir, "1507DCamera_${newTag}.apk")
                    if (targetFile.exists()) targetFile.delete()

                    var curUrl = downloadUrl
                    var redirects = 0
                    var conn: HttpURLConnection
                    while (true) {
                        val u = URL(curUrl)
                        conn = u.openConnection() as HttpURLConnection
                        conn.instanceFollowRedirects = false
                        conn.connectTimeout = 15000
                        conn.readTimeout = 30000
                        conn.setRequestProperty("User-Agent", "1507DCamera-App")
                        conn.connect()
                        val code = conn.responseCode
                        if (code in 300..399) {
                            val loc = conn.getHeaderField("Location")
                            conn.disconnect()
                            if (loc == null || redirects++ >= 5) return@withContext null
                            curUrl = loc
                        } else if (code == 200) {
                            break
                        } else {
                            conn.disconnect()
                            return@withContext null
                        }
                    }

                    conn.inputStream.use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    conn.disconnect()

                    if (targetFile.exists() && targetFile.length() > 1_000_000) {
                        targetFile
                    } else {
                        targetFile.delete()
                        null
                    }
                } catch (e: Exception) {
                    null
                }
            }

            btnCheckUpdate.isEnabled = true
            pbUpdate.visibility = View.GONE

            if (apkFile != null) {
                tvUpdateStatus.text = "Đã tải về thành công ($newTag)!\nFile: ${apkFile.name}"
                tvUpdateStatus.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.status_done_text))
                btnInstallNow.visibility = View.VISIBLE
                btnInstallNow.text = "CÀI ĐẶT BẢN $newTag"
                btnInstallNow.setOnClickListener {
                    installApk(apkFile)
                }
                Toast.makeText(this@SettingsActivity, "Đã tải về bản $newTag thành công!", Toast.LENGTH_SHORT).show()
            } else {
                tvUpdateStatus.text = "Tải bản cập nhật thất bại. Vui lòng thử lại sau."
                tvUpdateStatus.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.error))
                Toast.makeText(this@SettingsActivity, "Lỗi khi tải file APK cập nhật", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun installApk(file: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!packageManager.canRequestPackageInstalls()) {
                    val manageIntent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(manageIntent)
                    Toast.makeText(this, "Vui lòng cho phép cài đặt ứng dụng từ nguồn này rồi mở lại để cài đặt", Toast.LENGTH_LONG).show()
                    return
                }
            }

            val apkUri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Lỗi khởi chạy cài đặt: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
