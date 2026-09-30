package com.ttdk1507d.inspectioncamera.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object NetworkUtil {
    // LAN Wi-Fi is local; 1200ms is more than enough for a local ping (<50ms).
    private const val LAN_TIMEOUT_MS = 1200
    // Tailscale on 4G in Vietnam often relays via Singapore (DERP). Latency is 400-600ms,
    // so TCP handshake + HTTP GET needs a generous 4500ms timeout.
    private const val TAILSCALE_TIMEOUT_MS = 4500

    @Volatile
    private var lastWorkingUrl: String? = null
    @Volatile
    private var lastWorkingTimestamp: Long = 0L
    private const val CACHE_VALID_MS = 10_000L // 10s fast cache

    suspend fun resolveBaseUrl(lanUrl: String, tailscaleUrl: String): String? {
        return withContext(Dispatchers.IO) {
            val cleanLan = lanUrl.trimEnd('/')
            val cleanTs = tailscaleUrl.trimEnd('/')

            // Quick check: if last working URL was verified very recently (< 10s),
            // and it is still healthy, reuse it immediately (0ms wait for secondary).
            val cached = lastWorkingUrl
            val now = System.currentTimeMillis()
            if (cached != null && now - lastWorkingTimestamp < CACHE_VALID_MS) {
                val timeout = if (cached == cleanLan) LAN_TIMEOUT_MS else TAILSCALE_TIMEOUT_MS
                if (probeUrl(cached, timeout)) {
                    lastWorkingTimestamp = System.currentTimeMillis()
                    return@withContext cached
                }
            }

            // Probe LAN and Tailscale concurrently so 4G never suffers from sequential LAN timeout.
            val lanJob = async {
                if (cleanLan.isNotEmpty()) probeUrl(cleanLan, LAN_TIMEOUT_MS) else false
            }
            val tsJob = async {
                if (cleanTs.isNotEmpty()) probeUrlWithRetry(cleanTs, TAILSCALE_TIMEOUT_MS) else false
            }

            // Preferred route is LAN (station Wi-Fi)
            if (lanJob.await()) {
                tsJob.cancel()
                lastWorkingUrl = cleanLan
                lastWorkingTimestamp = System.currentTimeMillis()
                return@withContext cleanLan
            }

            // LAN failed or empty; check Tailscale which was probing in parallel
            if (tsJob.await()) {
                lastWorkingUrl = cleanTs
                lastWorkingTimestamp = System.currentTimeMillis()
                return@withContext cleanTs
            }

            // Both failed
            lastWorkingUrl = null
            null
        }
    }

    private fun probeUrlWithRetry(url: String, timeoutMs: Int): Boolean {
        if (probeUrl(url, timeoutMs)) return true
        // 1 quick retry for transient cellular/DERP packet drop
        return probeUrl(url, 2500)
    }

    private fun probeUrl(url: String, timeoutMs: Int): Boolean {
        return try {
            val clean = url.trimEnd('/')
            val conn = URL("$clean/api/health").openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            val ok = conn.responseCode == 200
            conn.disconnect()
            ok
        } catch (e: Exception) {
            false
        }
    }
}
