package com.ttdk1507d.inspectioncamera.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.button.MaterialButton
import com.ttdk1507d.inspectioncamera.R
import com.ttdk1507d.inspectioncamera.model.PhotoType
import java.io.File

data class PhotoReviewItem(
    val photoType: PhotoType,
    val seq: Int? = null,
    val file: File? = null,
    val remotePath: String? = null,
    val isPending: Boolean = false
) {
    val displayTitle: String
        get() = if (seq != null && photoType.multiPhoto) {
            "${photoType.label} #$seq"
        } else {
            photoType.label
        }
}

class PhotoReviewAdapter(
    private var items: List<PhotoReviewItem> = emptyList(),
    private val onRecaptureClick: (PhotoReviewItem) -> Unit,
    private val onDeleteClick: (PhotoReviewItem) -> Unit
) : RecyclerView.Adapter<PhotoReviewAdapter.PhotoViewHolder>() {

    fun updateItems(newItems: List<PhotoReviewItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_photo_review, parent, false)
        return PhotoViewHolder(view, onRecaptureClick, onDeleteClick)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class PhotoViewHolder(
        itemView: View,
        private val onRecaptureClick: (PhotoReviewItem) -> Unit,
        private val onDeleteClick: (PhotoReviewItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val ivThumb: ImageView = itemView.findViewById(R.id.iv_review_thumb)
        private val tvLabel: TextView = itemView.findViewById(R.id.tv_review_type_label)
        private val tvStatus: TextView = itemView.findViewById(R.id.tv_review_status)
        private val btnRecapture: MaterialButton = itemView.findViewById(R.id.btn_item_recapture)
        private val btnDelete: MaterialButton = itemView.findViewById(R.id.btn_item_delete)

        fun bind(item: PhotoReviewItem) {
            tvLabel.text = item.displayTitle

            if (item.isPending) {
                tvStatus.text = "⏳ Chờ gửi"
            } else {
                tvStatus.text = "✅ Đã lưu trên máy tính"
            }

            // Load thumbnail using Coil
            if (item.file != null && item.file.exists()) {
                ivThumb.load(item.file) {
                    crossfade(true)
                }
            } else {
                ivThumb.load(R.drawable.ic_camera)
            }

            btnRecapture.setOnClickListener { onRecaptureClick(item) }
            btnDelete.setOnClickListener { onDeleteClick(item) }
        }
    }
}
