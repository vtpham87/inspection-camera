package com.ttdk1507d.inspectioncamera

import android.app.Dialog
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
import com.ttdk1507d.inspectioncamera.firebase.FirebaseManager
import com.ttdk1507d.inspectioncamera.model.PhotoType
import com.ttdk1507d.inspectioncamera.util.NetworkUtil
import com.ttdk1507d.inspectioncamera.util.PlateUtil
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
        private const val TAG = "ReviewActivity"
        private val gson = Gson()
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
    private lateinit var btnDone: MaterialButton
    private var isSyncing = false

    private lateinit var adapter: PhotoReviewAdapter
    private val reviewItems = mutableListOf<PhotoReviewItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_review)

        prefs = PrefsManager(this)
        val rawPlate = intent.getStringExtra(EXTRA_PLATE) ?: ""
        val resolved = PlateUtil.resolveFullPlate(rawPlate, intent.getStringExtra(EXTRA_PLATE_COLOR))
        plate = resolved.basePlate
        plateColor = resolved.color
        lanKd = intent.getIntExtra(EXTRA_LAN_KD, resolved.lanKd)

        initViews()
        setupRecyclerView()
        loadPhotos()
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
        btnDone = findViewById(R.id.btn_review_done)

        toolbar.setNavigationOnClickListener {
            if (isSyncing) {
                Toast.makeText(this, "Đang đẩy ảnh về máy tính, vui lòng đợi giây lát...", Toast.LENGTH_SHORT).show()
            } else {
                finish()
            }
        }
        tvPlate.text = PlateUtil.formatCompactPlate(plate, plateColor, lanKd)

        btnDone.setOnClickListener {
            handleDoneClick()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSyncing) {
                    Toast.makeText(this@ReviewActivity, "Đang đẩy ảnh về máy tính, vui lòng đợi giây lát...", Toast.LENGTH_SHORT).show()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
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

        // 1. Quét thư mục review của biển số và lần kiểm định
        val reviewDir = if (lanKd > 1) File(filesDir, "review/$plate/$lanKd") else File(filesDir, "review/$plate")
        if (reviewDir.exists() && reviewDir.isDirectory) {
            val files = reviewDir.listFiles { f -> f.extension.equals("jpg", ignoreCase = true) } ?: emptyArray()
            for (file in files) {
                val item = parsePhotoReviewItem(file, isPending = false)
                if (item != null) {
                    val isUploaded = prefs.isPhotoUploaded(plate, lanKd, file.name)
                    reviewItems.add(item.copy(isPending = !isUploaded))
                }
            }
        }

        // 2. Quét thêm thư mục pending (nếu có ảnh từ phiên bản cũ chưa hoàn thành)
        val pendingDir = File(filesDir, "pending")
        if (pendingDir.exists() && pendingDir.isDirectory) {
            val metaFiles = pendingDir.listFiles { f -> f.extension == "meta" } ?: emptyArray()
            for (metaFile in metaFiles) {
                try {
                    val meta = gson.fromJson(metaFile.readText(), PendingUploadMetadata::class.java)
                    if (meta.plate.equals(plate, ignoreCase = true) && meta.lanKd == lanKd) {
                        val imgFile = File(pendingDir, meta.imageFileName)
                        if (imgFile.exists()) {
                            val photoType = PhotoType.fromApiName(meta.photoType)
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
                } catch (e: Exception) {
                    Log.w(TAG, "Lỗi đọc file meta: ${metaFile.name}: ${e.message}")
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
            } else {
                tvSyncStatus.text = getString(R.string.sync_pending_count, pendingCount)
                tvSyncStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                pbSync.visibility = View.GONE
            }
        }
    }

    private fun handleDoneClick() {
        if (isSyncing) {
            Toast.makeText(this, "Đang đẩy ảnh về máy tính, vui lòng đợi giây lát...", Toast.LENGTH_SHORT).show()
            return
        }

        val pendingToUpload = reviewItems.filter { it.isPending && it.file != null && it.file.exists() }
        if (pendingToUpload.isNotEmpty()) {
            uploadPhotosAndFinish(pendingToUpload)
        } else {
            prefs.markPlateDoneToday(plate, lanKd)
            finishAndGoHome()
        }
    }

    private fun uploadPhotosAndFinish(items: List<PhotoReviewItem>) {
        if (isSyncing) return

        isSyncing = true
        pbSync.visibility = View.VISIBLE
        btnDone.isEnabled = false
        val originalDoneText = getString(R.string.btn_done)
        btnDone.text = "ĐANG ĐẨY ẢNH VỀ MÁY TÍNH..."

        lifecycleScope.launch {
            val total = items.size
            var uploadedCount = 0

            for (item in items) {
                val imgFile = item.file ?: continue
                if (!imgFile.exists()) continue

                tvSyncStatus.text = "Đang đẩy ảnh về máy tính (${uploadedCount + 1}/$total)..."
                tvSyncStatus.setTextColor(ContextCompat.getColor(this@ReviewActivity, R.color.text_primary))

                val success = withContext(Dispatchers.IO) {
                    try {
                        FirebaseManager.uploadPhotoToInbox(
                            plate = plate,
                            plateColor = plateColor,
                            photoType = item.photoType.apiName,
                            seq = item.seq ?: 1,
                            lanKd = lanKd,
                            photoFile = imgFile
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Lỗi upload photo qua Firebase: ${e.message}", e)
                        false
                    }
                }

                if (success) {
                    prefs.markPhotoUploaded(plate, lanKd, imgFile.name)
                    if (imgFile.parentFile?.name == "pending") {
                        val baseName = imgFile.nameWithoutExtension
                        val metaFile = File(imgFile.parentFile, "$baseName.meta")
                        if (metaFile.exists()) metaFile.delete()
                        imgFile.delete()
                    }
                    uploadedCount++
                }
            }

            isSyncing = false
            pbSync.visibility = View.GONE
            btnDone.isEnabled = true
            btnDone.text = originalDoneText

            if (uploadedCount == total) {
                prefs.markPlateDoneToday(plate, lanKd)
                Toast.makeText(this@ReviewActivity, "Đã gửi thành công $uploadedCount ảnh về máy tính", Toast.LENGTH_SHORT).show()
                finishAndGoHome()
            } else if (uploadedCount > 0) {
                val remaining = total - uploadedCount
                Toast.makeText(this@ReviewActivity, "Đã gửi $uploadedCount/$total ảnh. Còn $remaining ảnh chưa gửi được!", Toast.LENGTH_LONG).show()
                tvSyncStatus.text = "⚠️ Còn $remaining ảnh chưa gửi được. Bấm Hoàn thành để thử lại."
                tvSyncStatus.setTextColor(ContextCompat.getColor(this@ReviewActivity, R.color.error))
                loadPhotos()
            } else {
                Toast.makeText(this@ReviewActivity, "Không thể gửi ảnh về máy tính. Vui lòng kiểm tra mạng!", Toast.LENGTH_LONG).show()
                tvSyncStatus.text = getString(R.string.sync_failed)
                tvSyncStatus.setTextColor(ContextCompat.getColor(this@ReviewActivity, R.color.error))
                loadPhotos()
            }
        }
    }

    private fun parsePhotoReviewItem(file: File, isPending: Boolean): PhotoReviewItem? {
        val nameWithoutExt = file.nameWithoutExtension
        for (type in PhotoType.ALL) {
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
                val baseUrl = NetworkUtil.resolveBaseUrl(prefs)
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
                Log.w(TAG, "Không thể kết nối máy chủ để xóa ảnh (sẽ dọn dẹp cục bộ): ${e.message}")
            }

            // 2. Delete local files
            val deletedFileName = item.file?.name
            item.file?.delete()
            if (deletedFileName != null) {
                prefs.clearPhotoUploaded(plate, lanKd, deletedFileName)
            }
            prefs.clearPlateDoneToday(plate, lanKd)

            // 3. Delete any matching meta files in pending
            val pendingDir = File(filesDir, "pending")
            if (pendingDir.exists()) {
                val metaFiles = pendingDir.listFiles { f -> f.extension == "meta" } ?: emptyArray()
                for (mf in metaFiles) {
                    try {
                        val meta = gson.fromJson(mf.readText(), PendingUploadMetadata::class.java)
                        if (meta.plate == plate && meta.photoType == item.photoType.apiName && meta.seq == item.seq && meta.lanKd == lanKd) {
                            File(pendingDir, meta.imageFileName).delete()
                            mf.delete()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Lỗi xóa file pending khi xóa ảnh: ${mf.name}: ${e.message}")
                    }
                }
            }

            pbLoading.visibility = View.GONE
            Toast.makeText(this@ReviewActivity, R.string.photo_deleted, Toast.LENGTH_SHORT).show()
            loadPhotos()
        }
    }
}
