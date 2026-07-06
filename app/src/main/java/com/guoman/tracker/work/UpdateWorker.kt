package com.guoman.tracker.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.guoman.tracker.api.AliClient
import com.guoman.tracker.data.Store
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 后台定时任务:每隔几小时被系统唤醒一次,检查追更列表里每部剧有没有新集,
 * 有就转存到自己网盘的「国漫追更/剧名」文件夹里。
 *
 * 这里只做「读取 + 转存」,绝不删除任何文件。
 * 下载交给你在阿里云盘官方 App 里对该文件夹操作(会员满速)。
 */
class UpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val DRIVE_ROOT = "国漫追更"
        val VIDEO_EXTS = listOf(".mp4", ".mkv", ".ts", ".flv", ".avi", ".m4v", ".mov", ".rmvb")

        fun isVideo(name: String): Boolean {
            val low = name.lowercase()
            return VIDEO_EXTS.any { low.endsWith(it) }
        }

        const val WORK_NAME = "guoman_update"

        /**
         * 注册/更新周期性后台任务。系统会每隔 hours 小时唤醒一次(WorkManager 最小周期 15 分钟)。
         * App 关闭或后台也照跑,由系统调度,不常驻内存。
         */
        fun schedule(context: Context, hours: Int) {
            val h = hours.coerceAtLeast(1).toLong()
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(h, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /** 取消后台任务。 */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = Store(applicationContext)
        val rt = store.refreshToken
        if (rt.isEmpty()) return@withContext Result.success()  // 还没登录

        val ali = AliClient()
        if (!ali.refreshFromToken(rt)) return@withContext Result.retry()  // token 失效,稍后重试
        // 刷新后保存新的 refresh_token
        store.refreshToken = ali.refreshToken

        val trackList = store.getTrackList().toMutableList()
        if (trackList.isEmpty()) return@withContext Result.success()

        // 确保网盘根文件夹存在
        val rootId = ali.ensureFolder(DRIVE_ROOT) ?: return@withContext Result.retry()

        var changed = false
        for (item in trackList) {
            try {
                val shareToken = ali.getShareToken(item.shareId) ?: continue
                // 递归收集该剧下所有视频
                val videos = collectVideos(ali, item.shareId, shareToken, item.folderId)
                val newOnes = videos.filter { it.name !in item.doneEpisodes }
                if (newOnes.isEmpty()) continue

                // 网盘目标子文件夹
                val subId = ali.ensureFolder(item.name, rootId) ?: continue
                for (v in newOnes) {
                    if (ali.saveToDrive(item.shareId, shareToken, v.fileId, subId)) {
                        item.doneEpisodes.add(v.name)
                        changed = true
                    }
                    Thread.sleep(800)  // 轻微限速,避免风控
                }
            } catch (e: Exception) {
                // 单部剧出错不影响其它剧
            }
        }
        if (changed) store.saveTrackList(trackList)
        Result.success()
    }

    /** 递归收集分享文件夹下所有视频文件。 */
    private fun collectVideos(
        ali: AliClient, shareId: String, shareToken: String,
        parentId: String, depth: Int = 0,
    ): List<com.guoman.tracker.api.AliFile> {
        if (depth > 5) return emptyList()
        val result = mutableListOf<com.guoman.tracker.api.AliFile>()
        val items = ali.listShareFiles(shareId, shareToken, parentId)
        for (it in items) {
            if (it.isFolder) {
                result.addAll(collectVideos(ali, shareId, shareToken, it.fileId, depth + 1))
            } else if (isVideo(it.name)) {
                result.add(it)
            }
        }
        return result
    }
}
