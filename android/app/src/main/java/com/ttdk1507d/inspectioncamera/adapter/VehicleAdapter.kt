package com.ttdk1507d.inspectioncamera.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.ttdk1507d.inspectioncamera.R
import com.ttdk1507d.inspectioncamera.model.Vehicle

class VehicleAdapter(
    private var vehicles: List<Vehicle> = emptyList(),
    private val onItemClick: (Vehicle) -> Unit
) : RecyclerView.Adapter<VehicleAdapter.VehicleViewHolder>() {

    fun updateList(newList: List<Vehicle>) {
        vehicles = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VehicleViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_vehicle, parent, false)
        return VehicleViewHolder(view, onItemClick)
    }

    override fun onBindViewHolder(holder: VehicleViewHolder, position: Int) {
        holder.bind(vehicles[position])
    }

    override fun getItemCount(): Int = vehicles.size

    class VehicleViewHolder(
        itemView: View,
        private val onItemClick: (Vehicle) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val tvPlate: TextView = itemView.findViewById(R.id.tv_item_plate)
        private val tvPlateColor: TextView = itemView.findViewById(R.id.tv_item_plate_color)
        private val tvStatus: TextView = itemView.findViewById(R.id.tv_item_status)
        private val tvTicket: TextView = itemView.findViewById(R.id.tv_item_ticket)

        fun bind(vehicle: Vehicle) {
            // Hiển thị biển số sạch: nếu có hậu tố màu dính liền (15C-442.34T) thì bỏ chữ T/V/X để tránh lặp với badge
            val rawPlate = vehicle.plate.trim()
            val displayPlate = if (rawPlate.length > 5 && (rawPlate.endsWith("T", true) || rawPlate.endsWith("V", true) || rawPlate.endsWith("X", true))) {
                rawPlate.dropLast(1)
            } else {
                rawPlate
            }
            tvPlate.text = displayPlate

            // Badge màu biển trực quan
            when (vehicle.plateColor?.uppercase()) {
                "T" -> {
                    tvPlateColor.visibility = View.VISIBLE
                    tvPlateColor.text = "TRẮNG"
                    tvPlateColor.setBackgroundResource(R.drawable.bg_plate_badge_white)
                    tvPlateColor.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_primary))
                }
                "V" -> {
                    tvPlateColor.visibility = View.VISIBLE
                    tvPlateColor.text = "VÀNG"
                    tvPlateColor.setBackgroundResource(R.drawable.bg_plate_badge_yellow)
                    tvPlateColor.setTextColor(ContextCompat.getColor(itemView.context, R.color.plate_yellow_text))
                }
                "X" -> {
                    tvPlateColor.visibility = View.VISIBLE
                    tvPlateColor.text = "XANH"
                    tvPlateColor.setBackgroundResource(R.drawable.bg_plate_badge_blue)
                    tvPlateColor.setTextColor(ContextCompat.getColor(itemView.context, R.color.plate_blue_text))
                }
                else -> {
                    tvPlateColor.visibility = View.GONE
                }
            }

            // Trạng thái ảnh đã chụp (nếu đã có ảnh)
            val photoCount = vehicle.photosTaken.size
            if (photoCount > 0) {
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = "✓ $photoCount ảnh"
            } else {
                tvStatus.visibility = View.GONE
            }

            // Số phiếu ngắn gọn
            if (!vehicle.ticketNum.isNullOrBlank()) {
                val shortTicket = if (vehicle.ticketNum.contains("/")) vehicle.ticketNum.split("/")[0] else vehicle.ticketNum
                tvTicket.visibility = View.VISIBLE
                tvTicket.text = "#$shortTicket"
            } else {
                tvTicket.visibility = View.GONE
            }

            itemView.setOnClickListener { onItemClick(vehicle) }
        }
    }
}
