package com.ttdk1507d.inspectioncamera

import android.app.Dialog
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.gson.Gson
import com.ttdk1507d.inspectioncamera.adapter.PhotoReviewAdapter
import com.ttdk1507d.inspectioncamera.adapter.PhotoReviewItem
import com.ttdk1507d.inspectioncamera.api.ApiClient
import com.ttdk1507d.inspectioncamera.model.PhotoType
import com.ttdk1507d.inspectioncamera.util.NetworkUtil
import com.ttdk1507d.inspectioncamera.util.PrefsManager
import com.ttdk1507d.inspectioncamera.worker.PendingUploadMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class ReviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLATE = "extra_plate"
        const val EXTRA_PLATE_COLOR = "extra_plate_color"
        const val EXTRA_LAN_KD = "extra_lan_kd"
    }

    private lateinit var prefs: PrefsManager
    private lateinit var plate: String
    private var plateColor: String? = null
    private var lanKd: Int = 1

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvPlate: TextView
    private lateinit var tvPhotoCount: TextView
    private lateinit var rvPhotos: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var pbLoading: ProgressBar

    // Sync card
    private lateinit var cardSync: MaterialCardView
    private lateinit var tvSyncStatus: TextView
    private lateinit var pbSync: ProgressBar
    private lateinit var btnSync: MaterialButton
    private lateinit var btnDone: MaterialButton
    private var isSyncing = false

    private lateinit var adapter: PhotoReviewAdapter
    private val reviewItems = mutableListOf<PhotoReviewItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_review)

        prefs = PrefsManager(this)
        plate = intent.getStringExtra(EXTRA_PLATE) ?: ""
        plateColor = intent.getStringExtra(EXTRA_PLATE_COLOR)
        lanKd = intent.getIntExtra(EXTRA_LAN_KD, 1)

        initViews()
        setupRecyclerView()
        loadPhotos()

        if (reviewItems.any { it.isPending }) {
            uploadPendingPhotos()
        }
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar_review)
        tvPlate = findViewById(R.id.tv_review_plate)
        tvPhotoCount = findViewById(R.id.tv_review_photo_count)
        rvPhotos = findViewById(R.id.rv_review_photos)
        tvEmpty = findViewById(R.id.tv_empty_review)
        pbLoading = findViewById(R.id.pb_review_loading)

        cardSync = findViewById(R.id.card_review_sync)
        tvSyncStatus = findViewById(R.id.tv_review_sync_status)
        pbSync = findViewById(R.id.pb_review_sync)
        btnSync = findViewById(R.id.btn_review_sync)
        btnDone = findViewById(R.id.btn_review_done)

        toolbar.setNavigationOnClickListener { finish() }
        tvPlate.text = com.ttdk1507d.inspectioncamera.util.PlateUtil.formatCompactPlate(plate, plateColor, lanKd)

        btnSync.setOnClickListener {
            uploadPendingPhotos()
        }

        btnDone.setOnClickListener {
            finishAndGoHome()
        }
    }

    private fun finishAndGoHome() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }

    private fun setupRecyclerView() {
        adapter = PhotoReviewAdapter(
            items = reviewItems,
            onRecaptureClick = { item ->
                val intent = Intent(this, CameraActivity::class.java).apply {
                    putExtra(CameraActivity.EXTRA_PLATE, plate)
                    putExtra(CameraActivity.EXTRA_PLATE_COLOR, plateColor)
                    putExtra(CameraActivity.EXTRA_LAN_KD, lanKd)
                    putExtra(CameraActivity.EXTRA_FOCUS_TYPE, item.photoType.apiName)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
                finish()
            },
            onDeleteClick = { item ->
                confirmDelete(item)
            },
            onPhotoClick = { item ->
                showFullScreenPhoto(item)
            }
        )
        val isLand = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        rvPhotos.layoutManager = GridLayoutManager(this, if (isLand) 2 else 1)
        rvPhotos.adapter = adapter
    }

    private fun showFullScreenPhoto(item: PhotoReviewItem) {
        val file = item.file ?: return
        if (!file.exists()) return

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_full_photo)
        val ivFull = dialog.findViewById<ImageView>(R.id.iv_full_photo)
        val btnClose = dialog.findViewById<View>(R.id.btn_close_full_photo)
        val tvTitle = dialog.findViewById<TextView>(R.id.tv_full_photo_title)

        val compactTitle = com.ttdk1507d.inspectioncamera.util.PlateUtil.formatCompactPlate(plate, plateColor, lanKd)
        tvTitle.text = "${item.displayTitle} • $compactTitle"
        ivFull.load(file) {
            crossfade(true)
        }
        btnClose.setOnClickListener { dialog.dismiss() }
        ivFull.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun loadPhotos() {
        reviewItems.clear()

        // 1. Scan pending directory to find all pending (type, seq) for this plate & lanKd
        val pendingSet = mutableSetOf<Pair<PhotoType, Int?>>()
        val pendingDir = File(filesDir, "pending")
        val pendingMetaList = mutableListOf<PendingUploadMetadata>()
        if (pendingDir.exists() && pendingDir.isDirectory) {
            val metaFiles = pendingDir.listFiles { f -> f.extension == "meta" } ?: emptyArray()
            val gson = Gson()
            for (metaFile in metaFiles) {
                try {
                    val meta = gson.fromJson(metaFile.readText(), PendingUploadMetadata::class.java)
                    if (meta.plate.equals(plate, ignoreCase = true) && meta.lanKd == lanKd) {
                        val photoType = PhotoType.values().firstOrNull { it.apiName == meta.photoType }
                        if (photoType != null) {
                            pendingSet.add(photoType to meta.seq)
                            pendingMetaList.add(meta)
                        }
                    }
                } catch (e: Exception) {
                    // Ignore corrupted meta
                }
            }
        }

        // 2. Scan review directory for this plate & lanKd
        val reviewDir = if (lanKd > 1) File(filesDir, "review/$plate/$lanKd") else File(filesDir, "review/$plate")
        if (reviewDir.exists() && reviewDir.isDirectory) {
            val files = reviewDir.listFiles { f -> f.extension.equals("jpg", ignoreCase = true) } ?: emptyArray()
            for (file in files) {
                val item = parsePhotoReviewItem(file, isPending = false)
                if (item != null) {
                    val isPending = pendingSet.contains(item.photoType to item.seq)
                    reviewItems.add(item.copy(isPending = isPending))
                }
            }
        }

        // 3. Scan pending directory for any pending uploads not in reviewDir
        for (meta in pendingMetaList) {
            val imgFile = File(pendingDir, meta.imageFileName)
            if (imgFile.exists()) {
                val photoType = PhotoType.values().firstOrNull { it.apiName == meta.photoType }
                if (photoType != null) {
                    val alreadyPresent = reviewItems.any { it.photoType == photoType && it.seq == meta.seq }
                    if (!alreadyPresent) {
                        reviewItems.add(
                            PhotoReviewItem(
                                photoType = photoType,
                                seq = meta.seq,
                                file = imgFile,
                                isPending = true
                            )
                        )
                    }
                }
            }
        }

        adapter.updateItems(reviewItems)

        tvPhotoCount.text = "${reviewItems.size} ảnh"
        val pendingCount = reviewItems.count { it.isPending }
        if (reviewItems.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            cardSync.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            cardSync.visibility = View.VISIBLE
            if (pendingCount == 0) {
                tvSyncStatus.text = getString(R.string.sync_all_done)
                tvSyncStatus.setTextColor(ContextCompat.getColor(this, R.color.status_done_text))
                pbSync.visibility = View.GONE
                btnSync.visibility = View.GONE
            } else {
                tvSyncStatus.text = getString(R.string.sync_pending_count, pendingCount)
                tvSyncStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                btnSync.visibility = View.VISIBLE
                btnSync.text = getString(R.string.btn_sync_photos)
                pbSync.visibility = View.GONE
            }
        }
    }

    private fun uploadPendingPhotos() {
        if (isSyncing) return
        val pendingDir = File(filesDir, "pending")
        if (!pendingDir.exists() || !pendingDir.isDirectory) return

        val metaFiles = pendingDir.listFiles { f -> f.extension == "meta" } ?: emptyArray()
        val gson = Gson()
        val plateMetas = mutableListOf<Pair<File, PendingUploadMetadata>>()

        for (mf in metaFiles) {
            try {
                val meta = gson.fromJson(mf.readText(), PendingUploadMetadata::class.java)
                if (meta.plate.equals(plate, ignoreCase = true) && meta.lanKd == lanKd) {
                    plateMetas.add(mf to meta)
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        if (plateMetas.isEmpty()) {
            tvSyncStatus.text = getString(R.string.sync_all_done)
            tvSyncStatus.setTextColor(ContextCompat.getColor(this, R.color.status_done_text))
            btnSync.visibility = View.GONE
            pbSync.visibility = View.GONE
            return
        }

        isSyncing = true
        pbSync.visibility = View.VISIBLE
        btnSync.isEnabled = false

        lifecycleScope.launch {
            val baseUrl = withContext(Dispatchers.IO) {
                NetworkUtil.resolveBaseUrl(prefs.lanUrl, prefs.tailscaleUrl)
            }

            if (baseUrl == null) {
                isSyncing = false
                pbSync.visibility = View.GONE
                btnSync.isEnabled = true
                tvSyncStatus.text = getString(R.string.sync_failed)
                tvSyncStatus.setTextColor(ContextCompat.getColor(this@ReviewActivity, R.color.error))
                return@launch
            }

            val service = ApiClient.getService(baseUrl)

            // Ensure server has latest paths from device before saving
            withContext(Dispatchers.IO) {
                try {
                    val cfg = prefs.getAppConfig()
                    val body = mapOf<String, Any>(
                        "vehicle_list_enabled" to cfg.vehicleListEnabled,
                        "server_port" to cfg.serverPort,
                        "photo_save_dir" to cfg.photoSaveDir,
                        "passenger_path" to cfg.passengerPath,
                        "new_vehicle_path" to cfg.newVehiclePath,
                        "sync_new_vehicle_45" to cfg.syncNewVehicle45,
                        "jpeg_quality" to cfg.jpegQuality,
                        "plate_color_suffix" to cfg.plateColorSuffix,
                        "photo_resolution" to cfg.photoResolution
                    )
                    service.postConfig(body)
                } catch (e: Exception) {
                    // Non-blocking sync
                }
            }

            val total = plateMetas.size
            var uploadedCount = 0

            for ((metaFile, meta) in plateMetas) {
                val imgFile = File(pendingDir, meta.imageFileName)
                if (!imgFile.exists()) {
                    metaFile.delete()
                    continue
                }

                tvSyncStatus.text = getString(R.string.sync_in_progress, uploadedCount + 1, total)

                val success = withContext(Dispatchers.IO) {
                    try {
                        val fileReq = imgFile.readBytes().toRequestBody("image/jpeg".toMediaTypeOrNull())
                        val filePart = MultipartBody.Part.createFormData("file", meta.imageFileName, fileReq)
                        val plateReq = meta.plate.toRequestBody("text/plain".toMediaTypeOrNull())
                        val photoTypeReq = meta.photoType.toRequestBody("text/plain".toMediaTypeOrNull())
                        val colorReq = meta.plateColor?.toRequestBody("text/plain".toMediaTypeOrNull())
                        val seqReq = meta.seq?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())
                        val lanKdReq = meta.lanKd.toString().toRequestBody("text/plain".toMediaTypeOrNull())

                        val resp = service.uploadPhoto(filePart, plateReq, colorReq, photoTypeReq, seqReq, lanKdReq)
                        resp.isSuccessful && resp.body()?.get("ok") == true
                    } catch (e: Exception) {
                        false
                    }
                }

                if (success) {
                    imgFile.delete()
                    metaFile.delete()
                    uploadedCount++
                }
            }

            isSyncing = false
            pbSync.visibility = View.GONE
            btnSync.isEnabled = true
            loadPhotos()

            if (uploadedCount > 0) {
                Toast.makeText(this@ReviewActivity, "Đã gửi thành công $uploadedCount ảnh về máy tính", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun parsePhotoReviewItem(file: File, isPending: Boolean): PhotoReviewItem? {
        val nameWithoutExt = file.nameWithoutExtension
        for (type in PhotoType.values()) {
            if (nameWithoutExt.startsWith(type.apiName)) {
                val remainder = nameWithoutExt.removePrefix(type.apiName).removePrefix("_")
                val seq = remainder.toIntOrNull()
                return PhotoReviewItem(
                    photoType = type,
                    seq = seq,
                    file = file,
                    isPending = isPending
                )
            }
        }
        return null
    }

    private fun confirmDelete(item: PhotoReviewItem) {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_delete_title)
            .setMessage(getString(R.string.confirm_delete_msg))
            .setPositiveButton(R.string.dialog_yes) { _, _ ->
                deletePhoto(item)
            }
            .setNegativeButton(R.string.dialog_no, null)
            .show()
    }

    private fun deletePhoto(item: PhotoReviewItem) {
        lifecycleScope.launch {
            pbLoading.visibility = View.VISIBLE

            // 1. Delete on server
            try {
                val baseUrl = NetworkUtil.resolveBaseUrl(prefs.lanUrl, prefs.tailscaleUrl)
                if (baseUrl != null) {
                    val service = ApiClient.getService(baseUrl)
                    val body = mutableMapOf<String, Any?>(
                        "plate" to plate,
                        "plate_color" to plateColor,
                        "photo_type" to item.photoType.apiName,
                        "seq" to item.seq,
                        "lan_kd" to lanKd
                    )
                    withContext(Dispatchers.IO) {
                        service.deletePhoto(body)
                    }
                }
            } catch (e: Exception) {
                // If offline, proceed with local cleanup
            }

            // 2. Delete local files
            item.file?.delete()

            // 3. Delete any matching meta files in pending
            val pendingDir = File(filesDir, "pending")
            if (pendingDir.exists()) {
                val metaFiles = pendingDir.listFiles { f -> f.extension == "meta" } ?: emptyArray()
                val gson = Gson()
                for (mf in metaFiles) {
                    try {
                        val meta = gson.fromJson(mf.readText(), PendingUploadMetadata::class.java)
                        if (meta.plate == plate && meta.photoType == item.photoType.apiName && meta.seq == item.seq && meta.lanKd == lanKd) {
                            File(pendingDir, meta.imageFileName).delete()
                            mf.delete()
                        }
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }

            pbLoading.visibility = View.GONE
            Toast.makeText(this@ReviewActivity, R.string.photo_deleted, Toast.LENGTH_SHORT).show()
            loadPhotos()
        }
    }
}
