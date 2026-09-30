package com.ttdk1507d.inspectioncamera.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object NetworkUtil {
    // LAN Wi-Fi is local; 1200ms is more than enough for a local ping (<50ms).
    private const val LAN_TIMEOUT_MS = 1200
    // Tailscale on 4G in Vietnam often relays via Singapore (DERP). Latency is 400-600ms,
    // so TCP handshake + HTTP GET needs a generous 4000ms timeout.
    private const val TAILSCALE_TIMEOUT_MS = 4000

    @Volatile
    private var lastWorkingUrl: String? = null
    @Volatile
    private var lastWorkingTimestamp: Long = 0L
    private const val CACHE_VALID_MS = 10_000L // 10s fast cache

    suspend fun resolveBaseUrl(prefs: PrefsManager): String? {
        return resolveBaseUrl(prefs.lanUrl, prefs.tailscaleUrl, prefs.lanEnabled, prefs.tailscaleEnabled)
    }

    suspend fun resolveBaseUrl(
        lanUrl: String,
        tailscaleUrl: String,
        lanEnabled: Boolean = true,
        tailscaleEnabled: Boolean = true
    ): String? {
        return withContext(Dispatchers.IO) {
            val cleanLan = lanUrl.trimEnd('/')
            val cleanTs = tailscaleUrl.trimEnd('/')

            val isLanActive = lanEnabled && cleanLan.isNotEmpty()
            val isTsActive = tailscaleEnabled && cleanTs.isNotEmpty()

            if (!isLanActive && !isTsActive) {
                lastWorkingUrl = null
                return@withContext null
            }

            // Quick check: if last working URL is still enabled and was verified recently (< 10s)
            val cached = lastWorkingUrl
            val now = System.currentTimeMillis()
            if (cached != null && now - lastWorkingTimestamp < CACHE_VALID_MS) {
                val cachedStillEnabled = (cached == cleanLan && isLanActive) || (cached == cleanTs && isTsActive)
                if (cachedStillEnabled) {
                    val timeout = if (cached == cleanLan) LAN_TIMEOUT_MS else TAILSCALE_TIMEOUT_MS
                    if (probeUrl(cached, timeout)) {
                        lastWorkingTimestamp = System.currentTimeMillis()
                        return@withContext cached
                    }
                }
            }

            // 1. If only LAN is enabled -> test LAN only, never probe Tailscale
            if (isLanActive && !isTsActive) {
                if (probeUrl(cleanLan, LAN_TIMEOUT_MS)) {
                    lastWorkingUrl = cleanLan
                    lastWorkingTimestamp = System.currentTimeMillis()
                    return@withContext cleanLan
                }
                lastWorkingUrl = null
                return@withContext null
            }

            // 2. If only Tailscale is enabled -> test Tailscale only, never probe LAN
            if (!isLanActive && isTsActive) {
                if (probeUrlWithRetry(cleanTs, TAILSCALE_TIMEOUT_MS)) {
                    lastWorkingUrl = cleanTs
                    lastWorkingTimestamp = System.currentTimeMillis()
                    return@withContext cleanTs
                }
                lastWorkingUrl = null
                return@withContext null
            }

            // 3. Both are enabled: sequential probe (LAN first ~15ms; if LAN unreachable, fallback to Tailscale)
            // Does not probe in parallel across networks.
            if (probeUrl(cleanLan, LAN_TIMEOUT_MS)) {
                lastWorkingUrl = cleanLan
                lastWorkingTimestamp = System.currentTimeMillis()
                return@withContext cleanLan
            }

            if (probeUrlWithRetry(cleanTs, TAILSCALE_TIMEOUT_MS)) {
                lastWorkingUrl = cleanTs
                lastWorkingTimestamp = System.currentTimeMillis()
                return@withContext cleanTs
            }

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
