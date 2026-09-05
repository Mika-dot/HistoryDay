package com.example.dayflash.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.dayflash.R
import com.example.dayflash.data.ClipEntity
import com.example.dayflash.databinding.ItemMomentBinding
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MomentAdapter(
    private val items: List<ClipEntity>,
    private val onClick: (ClipEntity) -> Unit,
) : RecyclerView.Adapter<MomentAdapter.MomentViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MomentViewHolder {
        val binding = ItemMomentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MomentViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MomentViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class MomentViewHolder(private val binding: ItemMomentBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ClipEntity, position: Int) {
            val context = binding.root.context
            val time = Instant.ofEpochMilli(item.capturedAt)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm"))

            binding.sequenceText.text = (position + 1).toString()
            binding.timeText.text = time
            binding.placeText.text = when {
                !item.placeName.isNullOrBlank() -> item.placeName
                item.latitude != null && item.longitude != null -> context.getString(R.string.map_point)
                else -> context.getString(R.string.no_location)
            }
            binding.metaText.text = if (item.latitude != null && item.longitude != null) {
                String.format(Locale.US, "%.5f, %.5f", item.latitude, item.longitude)
            } else {
                context.getString(R.string.location_not_saved)
            }
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}
