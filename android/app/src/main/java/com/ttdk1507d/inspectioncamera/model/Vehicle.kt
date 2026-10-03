package com.ttdk1507d.inspectioncamera.model

import com.google.firebase.database.IgnoreExtraProperties
import com.google.firebase.database.PropertyName
import com.google.gson.annotations.SerializedName

@IgnoreExtraProperties
data class Vehicle(
    @get:PropertyName("plate") @set:PropertyName("plate")
    var plate: String = "",

    @get:PropertyName("plate_clean") @set:PropertyName("plate_clean")
    @SerializedName("plate_clean")
    var plateClean: String = "",

    @get:PropertyName("plate_color") @set:PropertyName("plate_color")
    @SerializedName("plate_color")
    var plateColor: String? = null,

    @get:PropertyName("ticket_num") @set:PropertyName("ticket_num")
    @SerializedName("ticket_num")
    var ticketNum: String? = null,

    @get:PropertyName("sophieu") @set:PropertyName("sophieu")
    var sophieu: String? = null,

    @get:PropertyName("vehicle_type") @set:PropertyName("vehicle_type")
    @SerializedName("vehicle_type")
    var vehicleType: String = "",

    @get:PropertyName("brand") @set:PropertyName("brand")
    var brand: String = "",

    @get:PropertyName("owner") @set:PropertyName("owner")
    var owner: String = "",

    @get:PropertyName("time") @set:PropertyName("time")
    var time: String = "",

    @get:PropertyName("result") @set:PropertyName("result")
    var result: Int = 0,

    @get:PropertyName("photos_taken") @set:PropertyName("photos_taken")
    @SerializedName("photos_taken")
    var photosTaken: List<String> = emptyList(),

    @get:PropertyName("lan_kd") @set:PropertyName("lan_kd")
    @SerializedName("lan_kd")
    var lanKd: Int = 1,

    @get:PropertyName("suggest_lan_2") @set:PropertyName("suggest_lan_2")
    @SerializedName("suggest_lan_2")
    var suggestLan2: Boolean = false
) {
    constructor() : this("")
}
