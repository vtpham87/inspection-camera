package com.ttdk1507d.inspectioncamera.util

import android.content.Context
import android.content.SharedPreferences
import com.ttdk1507d.inspectioncamera.model.AppConfig
import com.ttdk1507d.inspectioncamera.model.TimestampConfig

class PrefsManager(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences("inspection_camera", Context.MODE_PRIVATE)
    )

    var lanIp: String
        get() = prefs.getString("lan_ip", "192.168.193.11") ?: "192.168.193.11"
        set(value) { prefs.edit().putString("lan_ip", value).commit() }

    var tailscaleIp: String
        get() = prefs.getString("tailscale_ip", "100.81.114.84") ?: "100.81.114.84"
        set(value) { prefs.edit().putString("tailscale_ip", value).commit() }

    var serverPort: Int
        get() = prefs.getInt("server_port", 8095)
        set(value) { prefs.edit().putInt("server_port", value).commit() }

    var vehicleListEnabled: Boolean
        get() = prefs.getBoolean("vehicle_list_enabled", true)
        set(value) { prefs.edit().putBoolean("vehicle_list_enabled", value).commit() }

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
        get() = prefs.getString("photo_save_dir", "D:\\Photos") ?: "D:\\Photos"
        set(value) { prefs.edit().putString("photo_save_dir", value).commit() }

    var passengerPath: String
        get() = prefs.getString("passenger_path", "D:\\Photos\\{date}\\{plate}") ?: "D:\\Photos\\{date}\\{plate}"
        set(value) { prefs.edit().putString("passenger_path", value).commit() }

    var newVehiclePath: String
        get() = prefs.getString("new_vehicle_path", "D:\\Photos\\{date}\\{plate}") ?: "D:\\Photos\\{date}\\{plate}"
        set(value) { prefs.edit().putString("new_vehicle_path", value).commit() }

    val lanUrl: String get() = "http://$lanIp:$serverPort"
    val tailscaleUrl: String get() = "http://$tailscaleIp:$serverPort"

    fun getAppConfig(): AppConfig {
        return AppConfig(
            vehicleListEnabled = vehicleListEnabled,
            serverPort = serverPort,
            photoSaveDir = photoSaveDir,
            passengerPath = passengerPath,
            newVehiclePath = newVehiclePath,
            syncNewVehicle45 = true,
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
}
