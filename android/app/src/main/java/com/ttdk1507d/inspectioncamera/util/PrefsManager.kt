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
        private var lastDateCheck: Long = 0
        
        fun getTodayString(): String {
            val now = System.currentTimeMillis()
            if (now - lastDateCheck > 60 * 60 * 1000) {
                cachedDate = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
                lastDateCheck = now
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
                else -> if (raw.isNullOrBlank()) "low" else raw
            }
        }
    }

    constructor(context: Context) : this(
        context.getSharedPreferences("inspection_camera", Context.MODE_PRIVATE)
    )

    var lanIp: String
        get() = prefs.getString("lan_ip", "192.168.193.11") ?: "192.168.193.11"
        set(value) { prefs.edit().putString("lan_ip", value).commit() }

    var tailscaleIp: String
        get() {
            val v = prefs.getString("tailscale_ip", null)
            return if (v.isNullOrEmpty() || v == "100.81.114.84") DEFAULT_CLOUD_URL else v
        }
        set(value) { prefs.edit().putString("tailscale_ip", value).commit() }

    var lanEnabled: Boolean
        get() = prefs.getBoolean("lan_enabled", true)
        set(value) { prefs.edit().putBoolean("lan_enabled", value).commit() }

    var tailscaleEnabled: Boolean
        get() = prefs.getBoolean("tailscale_enabled", true)
        set(value) { prefs.edit().putBoolean("tailscale_enabled", value).commit() }

    var serverPort: Int
        get() = prefs.getInt("server_port", 8095)
        set(value) { prefs.edit().putInt("server_port", value).commit() }

    var vehicleListEnabled: Boolean
        get() = prefs.getBoolean("vehicle_list_enabled", true)
        set(value) { prefs.edit().putBoolean("vehicle_list_enabled", value).commit() }

    var firebaseEnabled: Boolean
        get() = prefs.getBoolean("firebase_enabled", true)
        set(value) { prefs.edit().putBoolean("firebase_enabled", value).commit() }

    

    

    

    

    

    var photoResolution: String
        get() {
            val raw = prefs.getString("photo_resolution", "low") ?: "low"
            return normalizeResolution(raw)
        }
        set(value) {
            val normalized = normalizeResolution(value)
            prefs.edit().putString("photo_resolution", normalized).commit()
        }

    var jpegQuality: Int
        get() = prefs.getInt("jpeg_quality", 85)
        set(value) { prefs.edit().putInt("jpeg_quality", value).commit() }

    var plateColorSuffix: Boolean
        get() = prefs.getBoolean("plate_color_suffix", true)
        set(value) { prefs.edit().putBoolean("plate_color_suffix", value).commit() }

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
            prefs.edit().putString("timestamp_config_json", Gson().toJson(value)).commit()
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
        set(value) { prefs.edit().putString("upload_mode", value).commit() }

    var photoSaveDir: String
        get() = prefs.getString("photo_save_dir", "Z:\\Anh Phuong Tien") ?: "Z:\\Anh Phuong Tien"
        set(value) { prefs.edit().putString("photo_save_dir", value).commit() }

    var passengerPath: String
        get() = prefs.getString("passenger_path", "Z:\\Anh Khoang HK CCCD\\{plate}") ?: "Z:\\Anh Khoang HK CCCD\\{plate}"
        set(value) { prefs.edit().putString("passenger_path", value).commit() }

    var newVehiclePath: String
        get() = prefs.getString("new_vehicle_path", "Z:\\Anh sau cap mien\\{plate}") ?: "Z:\\Anh sau cap mien\\{plate}"
        set(value) { prefs.edit().putString("new_vehicle_path", value).commit() }

    var syncNewVehicle45: Boolean
        get() = prefs.getBoolean("sync_new_vehicle_45", true)
        set(value) { prefs.edit().putBoolean("sync_new_vehicle_45", value).commit() }

    var autoStartWithWindows: Boolean
        get() = prefs.getBoolean("auto_start_with_windows", true)
        set(value) { prefs.edit().putBoolean("auto_start_with_windows", value).commit() }

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
        } catch(e: Exception) {}
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

    fun markPlateDoneToday(plateClean: String, lanKd: Int) {
        val today = getTodayString()
        val key = "done_${today}_${plateClean.uppercase()}_$lanKd"
        prefs.edit().putLong(key, System.currentTimeMillis()).apply()
    }

    fun isPlateDoneToday(plateClean: String, lanKd: Int): Boolean {
        val today = getTodayString()
        val key = "done_${today}_${plateClean.uppercase()}_$lanKd"
        return prefs.getLong(key, 0L) > 0L
    }

    fun getPlateDoneTimestamp(plateClean: String, lanKd: Int): Long {
        val today = getTodayString()
        val key = "done_${today}_${plateClean.uppercase()}_$lanKd"
        return prefs.getLong(key, 0L)
    }

    fun clearPlateDoneToday(plateClean: String, lanKd: Int) {
        val today = getTodayString()
        val key = "done_${today}_${plateClean.uppercase()}_$lanKd"
        prefs.edit().remove(key).apply()
    }

    fun markPhotoUploaded(plateClean: String, lanKd: Int, fileName: String) {
        val today = getTodayString()
        val key = "photo_up_${today}_${plateClean.uppercase()}_${lanKd}_$fileName"
        prefs.edit().putBoolean(key, true).apply()
    }

    fun isPhotoUploaded(plateClean: String, lanKd: Int, fileName: String): Boolean {
        val today = getTodayString()
        val key = "photo_up_${today}_${plateClean.uppercase()}_${lanKd}_$fileName"
        return prefs.getBoolean(key, false)
    }

    fun clearPhotoUploaded(plateClean: String, lanKd: Int, fileName: String) {
        val today = getTodayString()
        val key = "photo_up_${today}_${plateClean.uppercase()}_${lanKd}_$fileName"
        prefs.edit().remove(key).apply()
    }
}
