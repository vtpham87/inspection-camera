package com.ttdk1507d.inspectioncamera.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

object NetworkUtil {
    private const val PROBE_TIMEOUT_MS = 1500L

    suspend fun resolveBaseUrl(lanUrl: String, tailscaleUrl: String): String {
        return withContext(Dispatchers.IO) {
            val lanReachable = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                var conn: HttpURLConnection? = null
                try {
                    val cleanLan = lanUrl.trimEnd('/')
                    conn = URL("$cleanLan/api/health").openConnection() as HttpURLConnection
                    conn.connectTimeout = 1500
                    conn.readTimeout = 1500
                    conn.requestMethod = "GET"
                    val code = conn.responseCode
                    code == 200
                } catch (e: Exception) {
                    false
                } finally {
                    conn?.disconnect()
                }
            } ?: false

            if (lanReachable) lanUrl else tailscaleUrl
        }
    }
}
