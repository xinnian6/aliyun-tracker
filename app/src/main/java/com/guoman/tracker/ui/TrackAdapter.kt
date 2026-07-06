package com.guoman.tracker.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.guoman.tracker.R
import com.guoman.tracker.data.TrackItem

/**
 * 追更清单适配器:显示每部剧的剧名 + 已记录集数。
 * 点击 = 查看缺集详情;长按 = 删除。
 */
class TrackAdapter(
    private val onClick: (TrackItem) -> Unit,
    private val onLongClick: (TrackItem) -> Unit,
) : RecyclerView.Adapter<TrackAdapter.VH>() {

    private val items = mutableListOf<TrackItem>()

    fun submit(list: List<TrackItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvTrackName)
        val info: TextView = view.findViewById(R.id.tvTrackInfo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_track, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
        holder.name.text = t.name
        holder.info.text = "已记录 ${t.doneEpisodes.size} 集  ·  点击查看缺集,长按删除"
        holder.itemView.setOnClickListener { onClick(t) }
        holder.itemView.setOnLongClickListener { onLongClick(t); true }
    }

    override fun getItemCount() = items.size
}
