package com.ttdk1507d.inspectioncamera.model

import com.google.gson.annotations.SerializedName

data class BackupConfig(
    @SerializedName("app") val app: String = "1507DCamera",
    @SerializedName("version") val version: Int = 1,
    @SerializedName("export_time") val exportTime: String? = null,
    @SerializedName("lan_ip") val lanIp: String = "192.168.193.11",
    @SerializedName("tailscale_ip") val tailscaleIp: String = "100.81.114.84",
    @SerializedName("server_port") val serverPort: Int = 8095,
    @SerializedName("vehicle_list_enabled") val vehicleListEnabled: Boolean = true,
    @SerializedName("photo_save_dir") val photoSaveDir: String = "D:\\Photos",
    @SerializedName("passenger_path") val passengerPath: String = "D:\\Photos\\{date}\\{plate}",
    @SerializedName("new_vehicle_path") val newVehiclePath: String = "D:\\Photos\\{date}\\{plate}",
    @SerializedName("timestamp_enabled") val timestampEnabled: Boolean = true,
    @SerializedName("timestamp_format") val timestampFormat: String = "HH:mm:ss - dd/MM/yyyy",
    @SerializedName("timestamp_position") val timestampPosition: String = "bottom_right",
    @SerializedName("timestamp_font_size") val timestampFontSize: Int = 28,
    @SerializedName("timestamp_stroke_enabled") val timestampStrokeEnabled: Boolean = true,
    @SerializedName("photo_resolution") val photoResolution: String = "low",
    @SerializedName("jpeg_quality") val jpegQuality: Int = 85,
    @SerializedName("upload_mode") val uploadMode: String = "review"
)
