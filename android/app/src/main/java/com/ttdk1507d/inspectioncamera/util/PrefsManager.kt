package com.ttdk1507d.inspectioncamera.util

import android.content.Context
import android.content.SharedPreferences

class PrefsManager(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences("inspection_camera", Context.MODE_PRIVATE)
    )

    var lanIp: String
        get() = prefs.getString("lan_ip", "192.168.193.11") ?: "192.168.193.11"
        set(value) = prefs.edit().putString("lan_ip", value).apply()

    var tailscaleIp: String
        get() = prefs.getString("tailscale_ip", "100.81.114.84") ?: "100.81.114.84"
        set(value) = prefs.edit().putString("tailscale_ip", value).apply()

    var serverPort: Int
        get() = prefs.getInt("server_port", 8095)
        set(value) = prefs.edit().putInt("server_port", value).apply()

    var vehicleListEnabled: Boolean
        get() = prefs.getBoolean("vehicle_list_enabled", true)
        set(value) = prefs.edit().putBoolean("vehicle_list_enabled", value).apply()

    val lanUrl: String get() = "http://$lanIp:$serverPort"
    val tailscaleUrl: String get() = "http://$tailscaleIp:$serverPort"
}
