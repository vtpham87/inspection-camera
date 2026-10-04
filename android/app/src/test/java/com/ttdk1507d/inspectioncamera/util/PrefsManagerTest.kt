package com.ttdk1507d.inspectioncamera.util

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PrefsManagerTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var prefsManager: PrefsManager

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        prefsManager = PrefsManager(fakePrefs)
    }

    @Test
    fun testDefaultValues() {
        assertEquals("192.168.193.11", prefsManager.lanIp)
        assertEquals(PrefsManager.DEFAULT_CLOUD_URL, prefsManager.tailscaleIp)
        assertTrue(prefsManager.lanEnabled)
        assertTrue(prefsManager.tailscaleEnabled)
        assertEquals(8095, prefsManager.serverPort)
        assertTrue(prefsManager.vehicleListEnabled)
        assertEquals("http://192.168.193.11:8095", prefsManager.lanUrl)
        assertEquals(PrefsManager.DEFAULT_CLOUD_URL, prefsManager.tailscaleUrl)
    }

    @Test
    fun testSetLanEnabled() {
        prefsManager.lanEnabled = false
        assertFalse(prefsManager.lanEnabled)
        prefsManager.lanEnabled = true
        assertTrue(prefsManager.lanEnabled)
    }

    @Test
    fun testSetTailscaleEnabled() {
        prefsManager.tailscaleEnabled = false
        assertFalse(prefsManager.tailscaleEnabled)
        prefsManager.tailscaleEnabled = true
        assertTrue(prefsManager.tailscaleEnabled)
    }

    @Test
    fun testSetLanIp() {
        prefsManager.lanIp = "192.168.1.100"
        assertEquals("192.168.1.100", prefsManager.lanIp)
        assertEquals("http://192.168.1.100:8095", prefsManager.lanUrl)
    }

    @Test
    fun testSetTailscaleIp() {
        prefsManager.tailscaleIp = "100.90.80.70"
        assertEquals("100.90.80.70", prefsManager.tailscaleIp)
        assertEquals("http://100.90.80.70:8095", prefsManager.tailscaleUrl)
    }

    @Test
    fun testSetServerPort() {
        prefsManager.serverPort = 9000
        assertEquals(9000, prefsManager.serverPort)
        assertEquals("http://192.168.193.11:9000", prefsManager.lanUrl)
        assertEquals(PrefsManager.DEFAULT_CLOUD_URL, prefsManager.tailscaleUrl)
    }

    @Test
    fun testSetVehicleListEnabled() {
        prefsManager.vehicleListEnabled = false
        assertFalse(prefsManager.vehicleListEnabled)
        prefsManager.vehicleListEnabled = true
        assertTrue(prefsManager.vehicleListEnabled)
    }

    @Test
    fun testUploadMode() {
        assertEquals("review", prefsManager.uploadMode)
        prefsManager.uploadMode = "immediate"
        assertEquals("immediate", prefsManager.uploadMode)
    }

    @Test
    fun testCustomUrlCombinations() {
        prefsManager.lanIp = "10.0.0.1"
        prefsManager.tailscaleIp = "100.64.0.1"
        prefsManager.serverPort = 8080

        assertEquals("http://10.0.0.1:8080", prefsManager.lanUrl)
        assertEquals("http://100.64.0.1:8080", prefsManager.tailscaleUrl)
    }

    @Test
    fun testPlateColorSuffixDefaultTrue() {
        assertTrue(prefsManager.plateColorSuffix)
    }

    @Test
    fun testBackupAndRestore() {
        prefsManager.lanIp = "192.168.1.99"
        prefsManager.tailscaleIp = "100.81.1.99"
        prefsManager.serverPort = 8096
        prefsManager.photoSaveDir = "E:\\CustomPhotos"
        prefsManager.passengerPath = "E:\\CustomPhotos\\{plate}"
        prefsManager.newVehiclePath = "E:\\CustomPhotos\\NV\\{plate}"
        prefsManager.photoResolution = "high"
        prefsManager.jpegQuality = 90
        prefsManager.uploadMode = "immediate"

        val backup = prefsManager.exportBackupConfig()
        assertEquals("192.168.1.99", backup.lanIp)
        assertEquals("100.81.1.99", backup.tailscaleIp)
        assertEquals(8096, backup.serverPort)
        assertEquals("E:\\CustomPhotos", backup.photoSaveDir)

        // Reset to default
        val newPrefs = PrefsManager(FakeSharedPreferences())
        assertEquals("192.168.193.11", newPrefs.lanIp)

        // Restore via backup object
        newPrefs.restoreBackupConfig(backup)
        assertEquals("192.168.1.99", newPrefs.lanIp)
        assertEquals("100.81.1.99", newPrefs.tailscaleIp)
        assertEquals(8096, newPrefs.serverPort)
        assertEquals("E:\\CustomPhotos", newPrefs.photoSaveDir)
        assertEquals("high", newPrefs.photoResolution)
        assertEquals(90, newPrefs.jpegQuality)
        assertEquals("immediate", newPrefs.uploadMode)
    }

    @Test
    fun testRestoreFromJson() {
        val json = """
            {
                "lan_ip": "192.168.1.200",
                "tailscale_ip": "100.81.2.200",
                "server_port": 8888,
                "photo_save_dir": "D:\\TestPhotos",
                "photo_resolution": "medium",
                "jpeg_quality": 95,
                "upload_mode": "review"
            }
        """.trimIndent()

        val success = prefsManager.restoreFromJson(json)
        assertTrue(success)
        assertEquals("192.168.1.200", prefsManager.lanIp)
        assertEquals("100.81.2.200", prefsManager.tailscaleIp)
        assertEquals(8888, prefsManager.serverPort)
        assertEquals("D:\\TestPhotos", prefsManager.photoSaveDir)
        assertEquals("medium", prefsManager.photoResolution)
        assertEquals(95, prefsManager.jpegQuality)
    }

    /**
     * In-memory FakeSharedPreferences implementation for JVM unit tests.
     */
    class FakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)

        override fun getString(key: String?, defValue: String?): String? {
            return (data[key] as? String) ?: defValue
        }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            return (data[key] as? MutableSet<String>) ?: defValues
        }

        override fun getInt(key: String?, defValue: Int): Int {
            return (data[key] as? Int) ?: defValue
        }

        override fun getLong(key: String?, defValue: Long): Long {
            return (data[key] as? Long) ?: defValue
        }

        override fun getFloat(key: String?, defValue: Float): Float {
            return (data[key] as? Float) ?: defValue
        }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            return (data[key] as? Boolean) ?: defValue
        }

        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(data)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class FakeEditor(private val backingMap: MutableMap<String, Any>) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearFlag = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) {
                    if (value != null) pending[key] = value else pending[key] = null
                }
                return this
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) {
                    if (values != null) pending[key] = values else pending[key] = null
                }
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) pending[key] = null
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearFlag = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearFlag) backingMap.clear()
                for ((k, v) in pending) {
                    if (v == null) backingMap.remove(k) else backingMap[k] = v
                }
            }
        }
    }
}
