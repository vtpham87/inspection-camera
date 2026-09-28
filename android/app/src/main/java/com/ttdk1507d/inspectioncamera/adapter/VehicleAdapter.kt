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
        private val tvTime: TextView = itemView.findViewById(R.id.tv_item_time)
        private val tvDetails: TextView = itemView.findViewById(R.id.tv_item_details)

        private val statusRear45: TextView = itemView.findViewById(R.id.status_rear_45)
        private val statusFront45: TextView = itemView.findViewById(R.id.status_front_45)
        private val statusChassis: TextView = itemView.findViewById(R.id.status_chassis)
        private val statusPassenger: TextView = itemView.findViewById(R.id.status_passenger)
        private val statusNewVehicle: TextView = itemView.findViewById(R.id.status_new_vehicle)

        fun bind(vehicle: Vehicle) {
            tvPlate.text = vehicle.plate

            // Plate color badge
            when (vehicle.plateColor?.uppercase()) {
                "T" -> {
                    tvPlateColor.visibility = View.VISIBLE
                    tvPlateColor.text = "Trắng"
                    tvPlateColor.setBackgroundResource(R.drawable.bg_plate_badge)
                    tvPlateColor.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_primary))
                }
                "V" -> {
                    tvPlateColor.visibility = View.VISIBLE
                    tvPlateColor.text = "Vàng"
                    tvPlateColor.setBackgroundResource(R.drawable.bg_plate_badge)
                    tvPlateColor.setTextColor(ContextCompat.getColor(itemView.context, R.color.plate_yellow_text))
                }
                "X" -> {
                    tvPlateColor.visibility = View.VISIBLE
                    tvPlateColor.text = "Xanh"
                    tvPlateColor.setBackgroundResource(R.drawable.bg_plate_badge)
                    tvPlateColor.setTextColor(ContextCompat.getColor(itemView.context, R.color.plate_blue_text))
                }
                else -> {
                    tvPlateColor.visibility = View.GONE
                }
            }

            val ticketStr = if (!vehicle.ticketNum.isNullOrBlank()) "Số phiếu: ${vehicle.ticketNum} • " else ""
            tvTime.text = "$ticketStr${vehicle.time}"

            val details = buildString {
                if (vehicle.vehicleType.isNotBlank()) append(vehicle.vehicleType)
                if (vehicle.brand.isNotBlank()) {
                    if (isNotEmpty()) append(" • ")
                    append(vehicle.brand)
                }
                if (vehicle.owner.isNotBlank()) {
                    if (isNotEmpty()) append(" • ")
                    append(vehicle.owner)
                }
            }
            tvDetails.text = details

            // Update photo status chips
            updateChip(statusRear45, "rear_45", "Sau", vehicle.photosTaken)
            updateChip(statusFront45, "front_45", "Trước", vehicle.photosTaken)
            updateChip(statusChassis, "chassis", "Khung", vehicle.photosTaken)
            updateChip(statusPassenger, "passenger", "Khách", vehicle.photosTaken)
            updateChip(statusNewVehicle, "new_vehicle", "Mới", vehicle.photosTaken)

            itemView.setOnClickListener { onItemClick(vehicle) }
        }

        private fun updateChip(chip: TextView, typeKey: String, baseLabel: String, takenList: List<String>) {
            val isTaken = takenList.contains(typeKey)
            if (isTaken) {
                chip.setBackgroundResource(R.drawable.bg_status_done)
                chip.setTextColor(ContextCompat.getColor(itemView.context, R.color.status_done_text))
                chip.text = "✓ $baseLabel"
            } else {
                chip.setBackgroundResource(R.drawable.bg_status_empty)
                chip.setTextColor(ContextCompat.getColor(itemView.context, R.color.status_empty_text))
                chip.text = baseLabel
            }
        }
    }
}
