package com.guoman.tracker.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.guoman.tracker.api.AliFile
import com.guoman.tracker.databinding.ItemFileBinding

/**
 * 分享文件列表适配器。
 * - 文件夹:显示文件夹图标,点击进入下一层
 * - 视频文件:显示大小,不可点进
 */
class FileAdapter(
    private val onFolderClick: (AliFile) -> Unit
) : RecyclerView.Adapter<FileAdapter.VH>() {

    private val items = mutableListOf<AliFile>()

    fun submit(list: List<AliFile>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(val b: ItemFileBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val it = items[position]
        holder.b.tvName.text = it.name
        if (it.isFolder) {
            holder.b.tvIcon.text = "📁"
            holder.b.tvSize.text = "文件夹"
            holder.b.root.setOnClickListener { onFolderClick(it) }
            holder.b.root.isClickable = true
        } else {
            holder.b.tvIcon.text = "🎬"
            holder.b.tvSize.text = humanSize(it.size)
            holder.b.root.setOnClickListener(null)
            holder.b.root.isClickable = false
        }
    }

    override fun getItemCount() = items.size

    private fun humanSize(n: Long): String {
        if (n <= 0) return ""
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = n.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return String.format("%.1f%s", v, units[i])
    }
}
