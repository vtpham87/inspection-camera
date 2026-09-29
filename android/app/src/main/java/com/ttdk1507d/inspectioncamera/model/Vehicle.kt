package com.ttdk1507d.inspectioncamera.model

import com.google.gson.annotations.SerializedName

data class Vehicle(
    val plate: String,
    @SerializedName("plate_clean") val plateClean: String,
    @SerializedName("plate_color") val plateColor: String?,
    @SerializedName("ticket_num") val ticketNum: String? = null,
    val sophieu: String? = null,
    @SerializedName("vehicle_type") val vehicleType: String,
    val brand: String,
    val owner: String,
    val time: String,
    val result: Int,
    @SerializedName("photos_taken") val photosTaken: List<String> = emptyList(),
    @SerializedName("lan_kd") val lanKd: Int = 1,
    @SerializedName("suggest_lan_2") val suggestLan2: Boolean = false
)
