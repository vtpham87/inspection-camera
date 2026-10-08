package com.ttdk1507d.inspectioncamera.model

enum class PhotoType(
    val apiName: String,
    val label: String,
    val prefix: String,
    val multiPhoto: Boolean = false
) {
    REAR_45("rear_45", "Ảnh phương tiện", ""),
    FRONT_45("front_45", "Ảnh biển số", "bs"),
    CHASSIS("chassis", "Số khung / Khoang máy", "sk_"),
    PASSENGER("passenger", "Khoang người lái", "", multiPhoto = true),
    NEW_VEHICLE("new_vehicle", "Xe mới / Sau miễn KĐ", "", multiPhoto = true);

    companion object {
        val ALL: Array<PhotoType> = values()
        private val BY_API_NAME: Map<String, PhotoType> = values().associateBy { it.apiName }
        fun fromApiName(name: String?): PhotoType? = if (name != null) BY_API_NAME[name] else null
    }
}
