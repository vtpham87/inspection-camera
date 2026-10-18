package com.ttdk1507d.inspectioncamera.util

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ttdk1507d.inspectioncamera.model.AppConfig
import com.ttdk1507d.inspectioncamera.model.BackupConfig
import com.ttdk1507d.inspectioncamera.model.TimestampConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PrefsManager(private val prefs: SharedPreferences) {

    companion object {
        private var cachedDate: String = ""
        private var cachedDateExpiry: Long = 0L

        /**
         * Ngày hôm nay (yyyyMMdd), đệm tới 0h00 hôm sau theo giờ máy. Trước đây đệm 1 giờ tính từ lần gọi đầu nên sau nửa đêm
         * có thể còn dùng ngày hôm qua tới 1 giờ (cờ "đã xong", "đã upload" của hôm qua bị coi là của hôm nay).
         * Đồng bộ hoá vì được gọi từ cả luồng Main lẫn IO.
         */
        @Synchronized
        fun getTodayString(): String {
            val now = System.currentTimeMillis()
            // now < expiry - 24h: đồng hồ bị chỉnh lùi -> tính lại
            if (now >= cachedDateExpiry || now < cachedDateExpiry - 24 * 60 * 60 * 1000L) {
                val nextMidnight = java.util.Calendar.getInstance().apply {
                    timeInMillis = now
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                    add(java.util.Calendar.DAY_OF_MONTH, 1)
                }.timeInMillis
                cachedDate = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(now))
                cachedDateExpiry = nextMidnight
            }
            return cachedDate
        }

        const val DEFAULT_CLOUD_URL = "https://cottage-charlotte-valuation-bestsellers.trycloudflare.com"

        fun normalizeResolution(raw: String?): String {
            return when (raw?.lowercase()?.trim()) {
                "720p", "hd", "low" -> "low"
                "1080p", "fhd", "medium" -> "medium"
                "4k", "high" -> "high"
                "original" -> "original"
                else -> "original"
            }
        }
    }

    constructor(context: Context) : this(
        context.getSharedPreferences("inspection_camera", Context.MODE_PRIVATE)
    )

    var lanIp: String
        get() = prefs.getString("lan_ip", "192.168.193.11") ?: "192.168.193.11"
        set(value) { prefs.edit().putString("lan_ip", value).apply() }

    var tailscaleIp: String
        get() {
            val v = prefs.getString("tailscale_ip", null)
            return if (v.isNullOrEmpty() || v == "100.81.114.84") DEFAULT_CLOUD_URL else v
        }
        set(value) { prefs.edit().putString("tailscale_ip", value).apply() }

    var lanEnabled: Boolean
        get() = prefs.getBoolean("lan_enabled", true)
        set(value) { prefs.edit().putBoolean("lan_enabled", value).apply() }

    var tailscaleEnabled: Boolean
        get() = prefs.getBoolean("tailscale_enabled", true)
        set(value) { prefs.edit().putBoolean("tailscale_enabled", value).apply() }

    var serverPort: Int
        get() = prefs.getInt("server_port", 8095)
        set(value) { prefs.edit().putInt("server_port", value).apply() }

    var vehicleListEnabled: Boolean
        get() = prefs.getBoolean("vehicle_list_enabled", true)
        set(value) { prefs.edit().putBoolean("vehicle_list_enabled", value).apply() }

    var firebaseEnabled: Boolean
        get() = prefs.getBoolean("firebase_enabled", true)
        set(value) { prefs.edit().putBoolean("firebase_enabled", value).apply() }

    var photoResolution: String
        get() {
            val raw = prefs.getString("photo_resolution", "low") ?: "low"
            return normalizeResolution(raw)
        }
        set(value) {
            val normalized = normalizeResolution(value)
            prefs.edit().putString("photo_resolution", normalized).apply()
        }

    var jpegQuality: Int
        get() = prefs.getInt("jpeg_quality", 85)
        set(value) { prefs.edit().putInt("jpeg_quality", value).apply() }

    var plateColorSuffix: Boolean
        get() = prefs.getBoolean("plate_color_suffix", true)
        set(value) { prefs.edit().putBoolean("plate_color_suffix", value).apply() }

    var timestampConfig: TimestampConfig
        get() {
            val json = prefs.getString("timestamp_config_json", null)
            return if (json != null) {
                try {
                    Gson().fromJson(json, TimestampConfig::class.java)
                } catch(e: Exception) { TimestampConfig() }
            } else TimestampConfig()
        }
        set(value) {
            prefs.edit().putString("timestamp_config_json", Gson().toJson(value)).apply()
        }
    var timestampEnabled: Boolean
        get() = timestampConfig.enabled
        set(value) { timestampConfig = timestampConfig.copy(enabled = value) }
    var timestampFormat: String
        get() = timestampConfig.format
        set(value) { timestampConfig = timestampConfig.copy(format = value) }
    var timestampFontSize: Int
        get() = timestampConfig.fontSize
        set(value) { timestampConfig = timestampConfig.copy(fontSize = value) }
    var timestampPosition: String
        get() = timestampConfig.position
        set(value) { timestampConfig = timestampConfig.copy(position = value) }
    var timestampStrokeEnabled: Boolean
        get() = timestampConfig.fontStrokeEnabled
        set(value) { timestampConfig = timestampConfig.copy(fontStrokeEnabled = value) }


    var uploadMode: String
        get() = prefs.getString("upload_mode", "review") ?: "review"
        set(value) { prefs.edit().putString("upload_mode", value).apply() }

    var photoSaveDir: String
        get() = prefs.getString("photo_save_dir", "Z:\\Anh Phuong Tien") ?: "Z:\\Anh Phuong Tien"
        set(value) { prefs.edit().putString("photo_save_dir", value).apply() }

    var passengerPath: String
        get() = prefs.getString("passenger_path", "Z:\\Anh Khoang HK CCCD\\{plate}") ?: "Z:\\Anh Khoang HK CCCD\\{plate}"
        set(value) { prefs.edit().putString("passenger_path", value).apply() }

    var newVehiclePath: String
        get() = prefs.getString("new_vehicle_path", "Z:\\Anh sau cap mien\\{plate}") ?: "Z:\\Anh sau cap mien\\{plate}"
        set(value) { prefs.edit().putString("new_vehicle_path", value).apply() }

    var syncNewVehicle45: Boolean
        get() = prefs.getBoolean("sync_new_vehicle_45", true)
        set(value) { prefs.edit().putBoolean("sync_new_vehicle_45", value).apply() }

    var autoStartWithWindows: Boolean
        get() = prefs.getBoolean("auto_start_with_windows", true)
        set(value) { prefs.edit().putBoolean("auto_start_with_windows", value).apply() }

    val lanUrl: String get() = NetworkUtil.formatUrl(lanIp, serverPort)
    val tailscaleUrl: String get() = NetworkUtil.formatUrl(tailscaleIp, serverPort)

    fun getAppConfig(): AppConfig {
        return AppConfig(
            vehicleListEnabled = vehicleListEnabled,
            serverPort = serverPort,
            photoSaveDir = photoSaveDir,
            passengerPath = passengerPath,
            newVehiclePath = newVehiclePath,
            syncNewVehicle45 = syncNewVehicle45,
            autoStartWithWindows = autoStartWithWindows,
            jpegQuality = jpegQuality,
            plateColorSuffix = plateColorSuffix,
            photoResolution = photoResolution,
            timestamp = timestampConfig
        )
    }

    fun saveFromAppConfig(config: AppConfig) {
        vehicleListEnabled = config.vehicleListEnabled
        serverPort = config.serverPort
        photoSaveDir = config.photoSaveDir
        passengerPath = config.passengerPath
        newVehiclePath = config.newVehiclePath
        syncNewVehicle45 = config.syncNewVehicle45
        autoStartWithWindows = config.autoStartWithWindows
        jpegQuality = config.jpegQuality
        plateColorSuffix = config.plateColorSuffix
        photoResolution = config.photoResolution
        timestampConfig = config.timestamp
    }

    fun exportBackupConfig(): BackupConfig {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return BackupConfig(
            exportTime = sdf.format(Date()),
            lanIp = lanIp,
            tailscaleIp = tailscaleIp,
            lanEnabled = lanEnabled,
            tailscaleEnabled = tailscaleEnabled,
            serverPort = serverPort,
            vehicleListEnabled = vehicleListEnabled,
            photoSaveDir = photoSaveDir,
            passengerPath = passengerPath,
            newVehiclePath = newVehiclePath,
            timestampConfigJson = Gson().toJson(timestampConfig),
            photoResolution = photoResolution,
            jpegQuality = jpegQuality,
            uploadMode = uploadMode
        )
    }

    fun restoreBackupConfig(config: BackupConfig) {
        lanIp = config.lanIp
        tailscaleIp = config.tailscaleIp
        lanEnabled = config.lanEnabled
        tailscaleEnabled = config.tailscaleEnabled
        serverPort = config.serverPort
        vehicleListEnabled = config.vehicleListEnabled
        photoSaveDir = config.photoSaveDir
        passengerPath = config.passengerPath
        newVehiclePath = config.newVehiclePath
        try {
            val tsConfig = Gson().fromJson(config.timestampConfigJson ?: "", TimestampConfig::class.java)
            if (tsConfig != null) timestampConfig = tsConfig
        } catch (e: Exception) {
            // timestampConfigJson hỏng: giữ nguyên cấu hình đóng dấu hiện tại
        }
        photoResolution = config.photoResolution
        jpegQuality = config.jpegQuality
        uploadMode = config.uploadMode
    }

    fun restoreFromJson(json: String): Boolean {
        return try {
            val gson = Gson()
            val jsonObj = gson.fromJson(json, JsonObject::class.java) ?: return false
            if (jsonObj.has("lan_ip")) {
                val backup = gson.fromJson(jsonObj, BackupConfig::class.java)
                restoreBackupConfig(backup)
                true
            } else if (jsonObj.has("photo_save_dir")) {
                val appConfig = gson.fromJson(jsonObj, AppConfig::class.java)
                saveFromAppConfig(appConfig)
                if (jsonObj.has("server_port")) {
                    serverPort = jsonObj.get("server_port").asInt
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Khoá theo ngày (done_/photo_up_/cert_ + yyyyMMdd + biển số + lần KĐ [+ tên file]); định dạng giữ nguyên để tương thích dữ liệu cũ. */
    private fun dayKey(prefix: String, plateClean: String, lanKd: Int, fileName: String? = null): String {
        val base = "${prefix}_${getTodayString()}_${plateClean.uppercase()}_$lanKd"
        return if (fileName != null) "${base}_$fileName" else base
    }

    fun markPlateDoneToday(plateClean: String, lanKd: Int) {
        prefs.edit().putLong(dayKey("done", plateClean, lanKd), System.currentTimeMillis()).apply()
    }

    fun isPlateDoneToday(plateClean: String, lanKd: Int): Boolean =
        prefs.getLong(dayKey("done", plateClean, lanKd), 0L) > 0L

    fun clearPlateDoneToday(plateClean: String, lanKd: Int) {
        prefs.edit().remove(dayKey("done", plateClean, lanKd)).apply()
    }

    fun markPhotoUploaded(plateClean: String, lanKd: Int, fileName: String) {
        prefs.edit().putBoolean(dayKey("photo_up", plateClean, lanKd, fileName), true).apply()
    }

    fun isPhotoUploaded(plateClean: String, lanKd: Int, fileName: String): Boolean =
        prefs.getBoolean(dayKey("photo_up", plateClean, lanKd, fileName), false)

    fun clearPhotoUploaded(plateClean: String, lanKd: Int, fileName: String) {
        prefs.edit().remove(dayKey("photo_up", plateClean, lanKd, fileName)).apply()
    }

    fun markCertIssued(plateClean: String, lanKd: Int, issued: Boolean) {
        val key = dayKey("cert", plateClean, lanKd)
        if (issued) prefs.edit().putBoolean(key, true).apply() else prefs.edit().remove(key).apply()
    }

    fun isCertIssued(plateClean: String, lanKd: Int): Boolean =
        prefs.getBoolean(dayKey("cert", plateClean, lanKd), false)

    /**
     * Các khoá theo ngày (done_/photo_up_/cert_ + yyyyMMdd) chỉ được đọc với ngày hôm nay nên khoá của ngày cũ vô dụng,
     * nhưng nếu không xoá thì SharedPreferences phình ra mỗi ngày (và mỗi apply() ghi lại toàn bộ file XML).
     */
    fun purgeStaleDailyKeys() {
        val today = getTodayString()
        val stale = prefs.all.keys.filter { k ->
            (k.startsWith("done_") || k.startsWith("photo_up_") || k.startsWith("cert_")) && !k.contains("_${today}_")
        }
        if (stale.isEmpty()) return
        val editor = prefs.edit()
        stale.forEach { editor.remove(it) }
        editor.apply()
    }
}
