package com.ttdk1507d.inspectioncamera

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.ttdk1507d.inspectioncamera.api.ApiClient
import com.ttdk1507d.inspectioncamera.util.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager

    private lateinit var toolbar: MaterialToolbar
    private lateinit var etLanIp: TextInputEditText
    private lateinit var etTailscaleIp: TextInputEditText
    private lateinit var etPort: TextInputEditText
    private lateinit var switchVehicleList: MaterialSwitch
    private lateinit var btnTest: MaterialButton
    private lateinit var tvTestResult: TextView
    private lateinit var btnSave: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = PrefsManager(this)

        initViews()
        loadCurrentSettings()
        setupListeners()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar_settings)
        etLanIp = findViewById(R.id.et_settings_lan_ip)
        etTailscaleIp = findViewById(R.id.et_settings_tailscale_ip)
        etPort = findViewById(R.id.et_settings_port)
        switchVehicleList = findViewById(R.id.switch_settings_vehicle_list)
        btnTest = findViewById(R.id.btn_settings_test)
        tvTestResult = findViewById(R.id.tv_settings_test_result)
        btnSave = findViewById(R.id.btn_settings_save)

        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun loadCurrentSettings() {
        etLanIp.setText(prefs.lanIp)
        etTailscaleIp.setText(prefs.tailscaleIp)
        etPort.setText(prefs.serverPort.toString())
        switchVehicleList.isChecked = prefs.vehicleListEnabled
    }

    private fun setupListeners() {
        btnTest.setOnClickListener {
            testConnections()
        }

        btnSave.setOnClickListener {
            saveSettings()
        }
    }

    private fun testConnections() {
        val lanIp = etLanIp.text?.toString()?.trim().orEmpty()
        val tailscaleIp = etTailscaleIp.text?.toString()?.trim().orEmpty()
        val port = etPort.text?.toString()?.trim()?.toIntOrNull() ?: prefs.serverPort

        val lanUrl = "http://$lanIp:$port"
        val tailscaleUrl = "http://$tailscaleIp:$port"

        btnTest.isEnabled = false
        tvTestResult.visibility = View.VISIBLE
        tvTestResult.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        tvTestResult.text = getString(R.string.test_testing)

        lifecycleScope.launch {
            val lanDeferred = async(Dispatchers.IO) { checkHealth(lanUrl) }
            val tsDeferred = async(Dispatchers.IO) { checkHealth(tailscaleUrl) }

            val lanOk = lanDeferred.await()
            val tsOk = tsDeferred.await()

            btnTest.isEnabled = true

            val resultText = buildString {
                append("LAN ($lanIp:$port): ")
                append(if (lanOk) "✅ Hoạt động" else "❌ Không phản hồi")
                append("\nTailscale ($tailscaleIp:$port): ")
                append(if (tsOk) "✅ Hoạt động" else "❌ Không phản hồi")
            }

            tvTestResult.text = resultText
            val resultColor = if (lanOk || tsOk) {
                R.color.status_done_text
            } else {
                R.color.error
            }
            tvTestResult.setTextColor(ContextCompat.getColor(this@SettingsActivity, resultColor))
        }
    }

    private suspend fun checkHealth(url: String): Boolean {
        return try {
            val service = ApiClient.getService(url)
            val resp = service.health()
            resp.isSuccessful && resp.body()?.get("ok") == true
        } catch (e: Exception) {
            false
        }
    }

    private fun saveSettings() {
        val lanIp = etLanIp.text?.toString()?.trim().orEmpty()
        val tailscaleIp = etTailscaleIp.text?.toString()?.trim().orEmpty()
        val port = etPort.text?.toString()?.trim()?.toIntOrNull()

        if (lanIp.isEmpty()) {
            etLanIp.error = "IP LAN không được để trống"
            return
        }

        if (tailscaleIp.isEmpty()) {
            etTailscaleIp.error = "IP Tailscale không được để trống"
            return
        }

        if (port == null || port !in 1..65535) {
            etPort.error = "Port phải từ 1 đến 65535"
            return
        }

        prefs.lanIp = lanIp
        prefs.tailscaleIp = tailscaleIp
        prefs.serverPort = port
        prefs.vehicleListEnabled = switchVehicleList.isChecked

        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        finish()
    }
}
