package com.ttdk1507d.inspectioncamera.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object NetworkUtil {
    private const val PROBE_TIMEOUT_MS = 2000

    suspend fun resolveBaseUrl(lanUrl: String, tailscaleUrl: String): String? {
        return withContext(Dispatchers.IO) {
            // 1. Probe LAN URL first (Wi-Fi in station)
            if (lanUrl.isNotEmpty() && probeUrl(lanUrl)) {
                return@withContext lanUrl
            }

            // 2. Probe Tailscale URL if LAN failed
            if (tailscaleUrl.isNotEmpty() && probeUrl(tailscaleUrl)) {
                return@withContext tailscaleUrl
            }

            // Neither is currently reachable
            null
        }
    }

    private fun probeUrl(url: String): Boolean {
        return try {
            val clean = url.trimEnd('/')
            val conn = URL("$clean/api/health").openConnection() as HttpURLConnection
            conn.connectTimeout = PROBE_TIMEOUT_MS
            conn.readTimeout = PROBE_TIMEOUT_MS
            conn.requestMethod = "GET"
            val ok = conn.responseCode == 200
            conn.disconnect()
            ok
        } catch (e: Exception) {
            false
        }
    }
}
