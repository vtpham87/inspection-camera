package com.ttdk1507d.inspectioncamera.model

enum class PhotoType(
    val apiName: String,
    val label: String,
    val prefix: String,
    val multiPhoto: Boolean = false
) {
    REAR_45("rear_45", "Góc SAU 45°", ""),
    FRONT_45("front_45", "Góc TRƯỚC 45°", "bs"),
    CHASSIS("chassis", "Số khung / Khoang máy", "sk_"),
    PASSENGER("passenger", "Khoang hành khách", "", multiPhoto = true),
    NEW_VEHICLE("new_vehicle", "Ảnh xe mới", "", multiPhoto = true);
}
