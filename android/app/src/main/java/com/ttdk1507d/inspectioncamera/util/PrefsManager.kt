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
        const val DEFAULT_CLOUD_URL = "https://cottage-charlotte-valuation-bestsellers.trycloudflare.com"
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

    var timestampEnabled: Boolean
        get() = prefs.getBoolean("timestamp_enabled", true)
        set(value) { prefs.edit().putBoolean("timestamp_enabled", value).commit() }

    var timestampFormat: String
        get() = prefs.getString("timestamp_format", "HH:mm:ss - dd/MM/yyyy") ?: "HH:mm:ss - dd/MM/yyyy"
        set(value) { prefs.edit().putString("timestamp_format", value).commit() }

    var timestampFontSize: Int
        get() = prefs.getInt("timestamp_font_size", 28)
        set(value) { prefs.edit().putInt("timestamp_font_size", value).commit() }

    var timestampPosition: String
        get() = prefs.getString("timestamp_position", "bottom_right") ?: "bottom_right"
        set(value) { prefs.edit().putString("timestamp_position", value).commit() }

    var timestampStrokeEnabled: Boolean
        get() = prefs.getBoolean("timestamp_stroke_enabled", true)
        set(value) { prefs.edit().putBoolean("timestamp_stroke_enabled", value).commit() }

    var photoResolution: String
        get() = prefs.getString("photo_resolution", "low") ?: "low"
        set(value) { prefs.edit().putString("photo_resolution", value).commit() }

    var jpegQuality: Int
        get() = prefs.getInt("jpeg_quality", 85)
        set(value) { prefs.edit().putInt("jpeg_quality", value).commit() }

    var plateColorSuffix: Boolean
        get() = prefs.getBoolean("plate_color_suffix", true)
        set(value) { prefs.edit().putBoolean("plate_color_suffix", value).commit() }

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
            jpegQuality = jpegQuality,
            plateColorSuffix = plateColorSuffix,
            photoResolution = photoResolution,
            timestamp = TimestampConfig(
                enabled = timestampEnabled,
                format = timestampFormat,
                fontSize = timestampFontSize,
                fontStrokeEnabled = timestampStrokeEnabled,
                position = timestampPosition
            )
        )
    }

    fun saveFromAppConfig(config: AppConfig) {
        vehicleListEnabled = config.vehicleListEnabled
        serverPort = config.serverPort
        photoSaveDir = config.photoSaveDir
        passengerPath = config.passengerPath
        newVehiclePath = config.newVehiclePath
        jpegQuality = config.jpegQuality
        plateColorSuffix = config.plateColorSuffix
        photoResolution = config.photoResolution
        timestampEnabled = config.timestamp.enabled
        timestampFormat = config.timestamp.format
        timestampFontSize = config.timestamp.fontSize
        timestampStrokeEnabled = config.timestamp.fontStrokeEnabled
        timestampPosition = config.timestamp.position
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
            timestampEnabled = timestampEnabled,
            timestampFormat = timestampFormat,
            timestampPosition = timestampPosition,
            timestampFontSize = timestampFontSize,
            timestampStrokeEnabled = timestampStrokeEnabled,
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
        timestampEnabled = config.timestampEnabled
        timestampFormat = config.timestampFormat
        timestampPosition = config.timestampPosition
        timestampFontSize = config.timestampFontSize
        timestampStrokeEnabled = config.timestampStrokeEnabled
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
}
