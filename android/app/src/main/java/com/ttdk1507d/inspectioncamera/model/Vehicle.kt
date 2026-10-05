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

    @get:PropertyName("ticket_int") @set:PropertyName("ticket_int")
    @SerializedName("ticket_int")
    var ticketInt: Int = 0,

    @get:PropertyName("sotem") @set:PropertyName("sotem")
    @SerializedName("sotem")
    var sotem: String? = null,

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
    var result: Int = -1,

    @get:PropertyName("photos_taken") @set:PropertyName("photos_taken")
    @SerializedName("photos_taken")
    var photosTaken: List<String> = emptyList(),

    @get:PropertyName("lan_kd") @set:PropertyName("lan_kd")
    @SerializedName("lan_kd")
    var lanKd: Int = 1,

    @get:PropertyName("suggest_lan_2") @set:PropertyName("suggest_lan_2")
    @SerializedName("suggest_lan_2")
    var suggestLan2: Boolean = false,

    @get:PropertyName("is_completed") @set:PropertyName("is_completed")
    @SerializedName("is_completed")
    var isCompleted: Boolean = false
) {
    constructor() : this("")

    fun getEffectiveTicketInt(): Int {
        if (ticketInt > 0) return ticketInt
        val raw = (ticketNum ?: sophieu).orEmpty().trim()
        val m = Regex("(\\d+)").find(raw)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    fun isFinished(): Boolean {
        if (isCompleted) return true
        val st = sotem
        if (!st.isNullOrBlank()) return true
        if (result == 0) return true
        if (result == 1 && !suggestLan2 && lanKd < 2) return true
        val photos = photosTaken
        val hasRear = photos.any { it.startsWith("rear_45") }
        val hasFront = photos.any { it.startsWith("front_45") }
        if (hasRear && hasFront) return true
        return false
    }
}
