package com.example.dayflash.ui

import android.media.MediaMetadataRetriever
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.dayflash.R
import com.example.dayflash.data.DaySummary
import com.example.dayflash.databinding.ItemDayBinding
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.Executors

class DaysAdapter(private val onClick: (String) -> Unit) : RecyclerView.Adapter<DaysAdapter.Holder>() {
    private val items = mutableListOf<DaySummary>()
    private val thumbnailExecutor = Executors.newFixedThreadPool(2)

    fun submit(newItems: List<DaySummary>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemDayBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        thumbnailExecutor.shutdownNow()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    inner class Holder(private val binding: ItemDayBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DaySummary) {
            val context = binding.root.context
            val date = runCatching { LocalDate.parse(item.dayKey) }.getOrNull()
            binding.dateText.text = date?.format(
                DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.getDefault())
            ) ?: item.dayKey
            binding.infoText.text = context.resources.getQuantityString(
                R.plurals.short_moments,
                item.count,
                item.count,
            )
            binding.root.setOnClickListener { onClick(item.dayKey) }
            binding.thumbnailImage.setImageDrawable(null)
            binding.thumbnailImage.setBackgroundResource(R.drawable.bg_thumbnail_placeholder)
            binding.thumbnailImage.tag = item.dayKey

            val path = item.previewPath ?: return
            if (!File(path).exists()) return
            thumbnailExecutor.execute {
                val bitmap = runCatching {
                    MediaMetadataRetriever().use { retriever ->
                        retriever.setDataSource(path)
                        retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                }.getOrNull()
                binding.thumbnailImage.post {
                    if (binding.thumbnailImage.tag == item.dayKey && bitmap != null) {
                        binding.thumbnailImage.background = null
                        binding.thumbnailImage.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }
}
