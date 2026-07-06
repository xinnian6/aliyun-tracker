package com.guoman.tracker.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地存储:用 SharedPreferences 保存
 *   - 登录凭证(refresh_token)
 *   - 追更列表(每部剧的 share_id / folder_id / 剧名 / 已记录集)
 *   - 检查间隔
 *
 * 凭证只存本机,不上传任何第三方。
 */
class Store(context: Context) {

    private val sp = context.getSharedPreferences("guoman_tracker", Context.MODE_PRIVATE)

    var refreshToken: String
        get() = sp.getString("refresh_token", "") ?: ""
        set(v) = sp.edit().putString("refresh_token", v).apply()

    var intervalHours: Int
        get() = sp.getInt("interval_hours", 6)
        set(v) = sp.edit().putInt("interval_hours", v).apply()

    /** 追更列表 */
    fun getTrackList(): List<TrackItem> {
        val raw = sp.getString("track_list", "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<TrackItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                TrackItem(
                    name = o.optString("name"),
                    shareId = o.optString("share_id"),
                    folderId = o.optString("folder_id"),
                    doneEpisodes = o.optJSONArray("done")?.let { j ->
                        (0 until j.length()).map { j.getString(it) }.toMutableSet()
                    } ?: mutableSetOf()
                )
            )
        }
        return list
    }

    fun saveTrackList(list: List<TrackItem>) {
        val arr = JSONArray()
        for (t in list) {
            val o = JSONObject()
                .put("name", t.name)
                .put("share_id", t.shareId)
                .put("folder_id", t.folderId)
                .put("done", JSONArray(t.doneEpisodes.toList()))
            arr.put(o)
        }
        sp.edit().putString("track_list", arr.toString()).apply()
    }

    fun addOrUpdateTrack(item: TrackItem) {
        val list = getTrackList().toMutableList()
        val idx = list.indexOfFirst { it.folderId == item.folderId }
        if (idx >= 0) list[idx] = item else list.add(item)
        saveTrackList(list)
    }

    fun removeTrack(folderId: String) {
        saveTrackList(getTrackList().filter { it.folderId != folderId })
    }
}

data class TrackItem(
    val name: String,
    val shareId: String,
    val folderId: String,
    val doneEpisodes: MutableSet<String> = mutableSetOf()
)
