package com.ttdk1507d.inspectioncamera

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
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
import java.io.File

class ReviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLATE = "extra_plate"
        const val EXTRA_PLATE_COLOR = "extra_plate_color"
    }

    private lateinit var prefs: PrefsManager
    private lateinit var plate: String
    private var plateColor: String? = null

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvPlate: TextView
    private lateinit var tvPhotoCount: TextView
    private lateinit var rvPhotos: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var pbLoading: ProgressBar

    private lateinit var adapter: PhotoReviewAdapter
    private val reviewItems = mutableListOf<PhotoReviewItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_review)

        prefs = PrefsManager(this)
        plate = intent.getStringExtra(EXTRA_PLATE) ?: ""
        plateColor = intent.getStringExtra(EXTRA_PLATE_COLOR)

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

        toolbar.setNavigationOnClickListener { finish() }
        tvPlate.text = plate
    }

    private fun setupRecyclerView() {
        adapter = PhotoReviewAdapter(
            items = reviewItems,
            onRecaptureClick = { item ->
                // Return to camera activity
                finish()
            },
            onDeleteClick = { item ->
                confirmDelete(item)
            }
        )
        rvPhotos.layoutManager = GridLayoutManager(this, 2)
        rvPhotos.adapter = adapter
    }

    private fun loadPhotos() {
        reviewItems.clear()

        // 1. Scan review directory for this plate
        val reviewDir = File(filesDir, "review/$plate")
        if (reviewDir.exists() && reviewDir.isDirectory) {
            val files = reviewDir.listFiles { f -> f.extension.equals("jpg", ignoreCase = true) } ?: emptyArray()
            for (file in files) {
                val item = parsePhotoReviewItem(file, isPending = false)
                if (item != null) reviewItems.add(item)
            }
        }

        // 2. Scan pending directory for pending uploads of this plate
        val pendingDir = File(filesDir, "pending")
        if (pendingDir.exists() && pendingDir.isDirectory) {
            val metaFiles = pendingDir.listFiles { f -> f.extension == "meta" } ?: emptyArray()
            val gson = Gson()
            for (metaFile in metaFiles) {
                try {
                    val meta = gson.fromJson(metaFile.readText(), PendingUploadMetadata::class.java)
                    if (meta.plate.equals(plate, ignoreCase = true)) {
                        val imgFile = File(pendingDir, meta.imageFileName)
                        if (imgFile.exists()) {
                            val photoType = PhotoType.values().firstOrNull { it.apiName == meta.photoType }
                            if (photoType != null) {
                                // Add to list if not already present
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
                    // Ignore corrupted meta
                }
            }
        }

        adapter.updateItems(reviewItems)

        tvPhotoCount.text = "${reviewItems.size} ảnh"
        if (reviewItems.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
        } else {
            tvEmpty.visibility = View.GONE
        }
    }

    private fun parsePhotoReviewItem(file: File, isPending: Boolean): PhotoReviewItem? {
        val nameWithoutExt = file.nameWithoutExtension // e.g. "rear_45" or "passenger_1"
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
                val service = ApiClient.getService(baseUrl)
                val body = mutableMapOf<String, Any?>(
                    "plate" to plate,
                    "photo_type" to item.photoType.apiName,
                    "seq" to item.seq
                )
                withContext(Dispatchers.IO) {
                    service.deletePhoto(body)
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
                        if (meta.plate == plate && meta.photoType == item.photoType.apiName && meta.seq == item.seq) {
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
