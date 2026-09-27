package com.ttdk1507d.inspectioncamera.model

import com.google.gson.annotations.SerializedName

data class TimestampConfig(
    val enabled: Boolean = true,
    val format: String = "HH:mm:ss - dd/MM/yyyy",
    @SerializedName("font_size") val fontSize: Int = 28,
    @SerializedName("font_color") val fontColor: String = "#FFFFFF",
    @SerializedName("font_bold") val fontBold: Boolean = true,
    @SerializedName("font_stroke_enabled") val fontStrokeEnabled: Boolean = true,
    @SerializedName("font_stroke_color") val fontStrokeColor: String = "#000000",
    @SerializedName("font_stroke_width") val fontStrokeWidth: Float = 2.0f,
    @SerializedName("background_color") val backgroundColor: String = "#80000000",
    val position: String = "bottom_right"
)

data class AppConfig(
    @SerializedName("vehicle_list_enabled") val vehicleListEnabled: Boolean = true,
    @SerializedName("server_port") val serverPort: Int = 8095,
    val paths: Map<String, String> = mapOf(
        "rear_45" to "D:\\Photos\\{date}",
        "front_45" to "D:\\Photos\\{date}",
        "chassis" to "D:\\Photos\\{date}",
        "passenger" to "D:\\Photos\\{date}\\{plate}",
        "new_vehicle" to "D:\\Photos\\{date}\\{plate}"
    ),
    @SerializedName("jpeg_quality") val jpegQuality: Int = 85,
    @SerializedName("plate_color_suffix") val plateColorSuffix: Boolean = true,
    val timestamp: TimestampConfig = TimestampConfig(),
    @SerializedName("photo_resolution") val photoResolution: String = "original"
)
