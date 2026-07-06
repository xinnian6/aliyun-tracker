package com.guoman.tracker.ui

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.guoman.tracker.api.AliClient
import com.guoman.tracker.api.AliFile
import com.guoman.tracker.api.Episode
import com.guoman.tracker.api.QrSession
import com.guoman.tracker.api.Utils
import com.guoman.tracker.data.Store
import com.guoman.tracker.data.TrackItem
import com.guoman.tracker.work.UpdateWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面:串起扫码登录、浏览分享、追更、清单管理、定时设置。
 *
 * 所有网络请求放到后台线程(Dispatchers.IO),避免卡界面。
 * 本 App 只做「读取分享 / 转存」,绝不删除网盘任何文件。
 */
class MainActivity : AppCompatActivity() {

    private val ali = AliClient()
    private lateinit var store: Store

    // 当前浏览的分享
    private var shareId = ""
    private var shareToken = ""
    private var curFolderId = "root"
    private var curFolderName = "根目录"
    private val pathStack = ArrayDeque<Pair<String, String>>()   // (folderId, name)
    private var items = listOf<AliFile>()

    private lateinit var tvAccount: TextView
    private lateinit var etLink: EditText
    private lateinit var btnRead: Button
    private lateinit var btnBack: Button
    private lateinit var tvPath: TextView
    private lateinit var rvFiles: RecyclerView
    private lateinit var btnTrack: Button
    private lateinit var rvTrack: RecyclerView
    private lateinit var etInterval: EditText
    private lateinit var btnSaveTimer: Button
    private lateinit var tvLog: TextView

    private lateinit var fileAdapter: FileAdapter
    private lateinit var trackAdapter: TrackAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(com.guoman.tracker.R.layout.activity_main)
        store = Store(this)

        bindViews()
        setupLists()
        setupActions()

        etInterval.setText(store.intervalHours.toString())
        refreshTrackList()

        // 启动时用本地保存的凭证自动登录
        val rt = store.refreshToken
        if (rt.isNotEmpty()) {
            log("正在用本地凭证登录...")
            io({ ali.refreshFromToken(rt) }) { ok ->
                if (ok) {
                    store.refreshToken = ali.refreshToken
                    tvAccount.text = "已登录:${ali.userName}"
                    log("登录成功:${ali.userName}")
                } else {
                    tvAccount.text = "凭证已失效,请重新扫码登录"
                    log("凭证失效,请点右上角重新登录")
                    showQrLogin()
                }
            }
        } else {
            tvAccount.text = "未登录"
            showQrLogin()
        }
    }

    private fun bindViews() {
        tvAccount = findViewById(com.guoman.tracker.R.id.tvAccount)
        etLink = findViewById(com.guoman.tracker.R.id.etLink)
        btnRead = findViewById(com.guoman.tracker.R.id.btnRead)
        btnBack = findViewById(com.guoman.tracker.R.id.btnBack)
        tvPath = findViewById(com.guoman.tracker.R.id.tvPath)
        rvFiles = findViewById(com.guoman.tracker.R.id.rvFiles)
        btnTrack = findViewById(com.guoman.tracker.R.id.btnTrack)
        rvTrack = findViewById(com.guoman.tracker.R.id.rvTrack)
        etInterval = findViewById(com.guoman.tracker.R.id.etInterval)
        btnSaveTimer = findViewById(com.guoman.tracker.R.id.btnSaveTimer)
        tvLog = findViewById(com.guoman.tracker.R.id.tvLog)
        tvLog.movementMethod = ScrollingMovementMethod()
        // 点账号栏可重新登录
        tvAccount.setOnClickListener { showQrLogin() }
    }

    private fun setupLists() {
        fileAdapter = FileAdapter(
            onFolderClick = { f -> enterFolder(f) }
        )
        rvFiles.layoutManager = LinearLayoutManager(this)
        rvFiles.adapter = fileAdapter

        trackAdapter = TrackAdapter(
            onClick = { t -> showTrackDetail(t) },
            onLongClick = { t -> confirmRemoveTrack(t) }
        )
        rvTrack.layoutManager = LinearLayoutManager(this)
        rvTrack.adapter = trackAdapter
    }

    private fun setupActions() {
        btnRead.setOnClickListener { onRead() }
        btnBack.setOnClickListener { onBack() }
        btnTrack.setOnClickListener { onTrackCurrent() }
        btnSaveTimer.setOnClickListener { onSaveTimer() }
    }

    // ---------------- 扫码登录 ----------------

    private fun showQrLogin() {
        val view = layoutInflater.inflate(com.guoman.tracker.R.layout.dialog_qrcode, null)
        val imgQr = view.findViewById<ImageView>(com.guoman.tracker.R.id.ivQrCode)
        val tvStatus = view.findViewById<TextView>(com.guoman.tracker.R.id.tvQrStatus)
        @Suppress("UNUSED_VARIABLE")
        val dialog = AlertDialog.Builder(this)
            .setTitle("扫码登录阿里云盘")
            .setView(view)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()

        tvStatus.text = "正在生成二维码..."
        io({ ali.generateQrCode() }) { qr ->
            if (qr == null) {
                tvStatus.text = "生成二维码失败,请检查网络后重试"
                return@io
            }
            val bmp = Utils.makeQr(qr.qrLink, 500)
            if (bmp != null) imgQr.setImageBitmap(bmp)
            tvStatus.text = "请用阿里云盘 App 扫码"
            pollQr(qr, dialog, tvStatus)
        }
    }

    private fun pollQr(qr: QrSession, dialog: AlertDialog, tvStatus: TextView) {
        lifecycleScope.launch {
            var tries = 0
            while (dialog.isShowing && tries < 100) {   // 最多约 5 分钟
                val status = withContext(Dispatchers.IO) { ali.queryQrCode(qr) }
                when (status) {
                    "SCANED" -> tvStatus.text = "已扫码,请在手机上确认登录"
                    "CONFIRMED" -> {
                        store.refreshToken = ali.refreshToken
                        tvAccount.text = "已登录:${ali.userName}"
                        log("登录成功:${ali.userName}")
                        dialog.dismiss()
                        return@launch
                    }
                    "EXPIRED", "ERROR" -> {
                        tvStatus.text = "二维码已过期,请关闭重试"
                        return@launch
                    }
                }
                tries++
                delay(3000)
            }
        }
    }

    // ---------------- 读取 / 浏览分享 ----------------

    private fun onRead() {
        val link = etLink.text.toString().trim()
        if (link.isEmpty()) { toast("请先粘贴分享链接"); return }
        if (ali.accessToken.isEmpty()) { toast("请先扫码登录"); showQrLogin(); return }
        val (sid, fid) = Utils.parseShareLink(link)
        if (sid.isEmpty()) { toast("链接里没解析出分享ID"); return }
        shareId = sid
        pathStack.clear()
        log("读取分享...")
        io({
            val token = ali.getShareToken(sid) ?: return@io null
            shareToken = token
            // 如果链接带了具体文件夹,取它真实名字
            val name = if (fid == "root") "根目录" else {
                // 用列表接口拿不到自身名字,这里先用占位,进入后由父级传入;
                // 直接进入该文件夹
                "此文件夹"
            }
            val list = ali.listShareFiles(sid, token, fid)
            Triple(fid, name, list)
        }) { result ->
            if (result == null) { log("读取失败,可能链接失效或需要提取码"); return@io }
            curFolderId = result.first
            curFolderName = result.second
            items = result.third
            renderFiles()
            tvPath.text = curFolderName
            btnBack.isEnabled = pathStack.isNotEmpty()
            log("共 ${items.size} 项")
        }
    }

    private fun enterFolder(f: AliFile) {
        pathStack.addLast(curFolderId to curFolderName)
        log("进入:${f.name}")
        io({
            val list = ali.listShareFiles(shareId, shareToken, f.fileId)
            Pair(f, list)
        }) { pair ->
            curFolderId = pair.first.fileId
            curFolderName = pair.first.name
            items = pair.second
            renderFiles()
            tvPath.text = pathStack.joinToString(" / ") { it.second } + " / " + curFolderName
            btnBack.isEnabled = true
        }
    }

    private fun onBack() {
        if (pathStack.isEmpty()) return
        val (fid, name) = pathStack.removeLast()
        log("返回:$name")
        io({
            val list = ali.listShareFiles(shareId, shareToken, fid)
            Pair(Pair(fid, name), list)
        }) { pair ->
            curFolderId = pair.first.first
            curFolderName = pair.first.second
            items = pair.second
            renderFiles()
            tvPath.text = if (pathStack.isEmpty()) curFolderName
                else pathStack.joinToString(" / ") { it.second } + " / " + curFolderName
            btnBack.isEnabled = pathStack.isNotEmpty()
        }
    }

    private fun renderFiles() {
        // 文件夹在前,视频按集号排序
        val folders = items.filter { it.isFolder }
        val files = items.filter { !it.isFolder }.sortedBy { Episode.episodeNum(it.name) }
        fileAdapter.submit(folders + files)
    }

    // ---------------- 追更当前文件夹 ----------------

    private fun onTrackCurrent() {
        if (curFolderId == "root" || curFolderId.isEmpty()) {
            toast("请先进入某部剧的集数文件夹再追更")
            return
        }
        if (ali.accessToken.isEmpty()) { toast("请先扫码登录"); return }
        val name = curFolderName
        AlertDialog.Builder(this)
            .setTitle("加入追更")
            .setMessage("把「$name」加入自动追更?\n\n· 立刻转存最新一集到网盘\n· 已有集记为基准(不转存)\n· 以后有新集自动转存")
            .setPositiveButton("确定") { _, _ -> doTrack(name) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doTrack(showName: String) {
        val fid = curFolderId
        val sid = shareId
        log("=== 追更《$showName》===")
        io({
            // 递归收集视频
            val videos = collectVideos(fid)
            if (videos.isEmpty()) return@io "空"
            // 网盘目标文件夹:国漫追更/剧名
            val rootId = ali.ensureFolder("国漫追更", "root") ?: return@io "建根目录失败"
            val subId = ali.ensureFolder(showName, rootId) ?: return@io "建剧目录失败"
            // 按集号排序,最后一个是最新集
            val sorted = videos.sortedBy { Episode.episodeNum(it.name) }
            val latest = sorted.last()
            // 转存最新集
            ali.saveToDrive(sid, shareToken, latest.fileId, subId)
            // 记录基准
            val done = videos.map { it.name }.toMutableSet()
            store.addOrUpdateTrack(TrackItem(showName, sid, fid, done))
            "成功:${videos.size}集,最新 ${latest.name}"
        }) { msg ->
            log("  $msg")
            refreshTrackList()
        }
    }

    /** 递归收集分享文件夹下所有视频(含子文件夹)。在 IO 线程调用。 */
    private fun collectVideos(folderId: String, depth: Int = 0): List<AliFile> {
        if (depth > 5) return emptyList()
        val result = mutableListOf<AliFile>()
        val list = ali.listShareFiles(shareId, shareToken, folderId)
        for (f in list) {
            if (f.isFolder) result.addAll(collectVideos(f.fileId, depth + 1))
            else if (Utils.isVideo(f.name)) result.add(f)
        }
        return result
    }

    // ---------------- 追更清单 ----------------

    private fun refreshTrackList() {
        trackAdapter.submit(store.getTrackList())
    }

    private fun showTrackDetail(t: TrackItem) {
        if (ali.accessToken.isEmpty()) { toast("请先扫码登录"); return }
        log("扫描《${t.name}》集数...")
        io({
            val token = ali.getShareToken(t.shareId) ?: return@io null
            shareToken = token
            shareId = t.shareId
            val videos = collectVideos(t.folderId)
            val names = videos.map { it.name }
            Episode.analyze(names)
        }) { info ->
            if (info == null) { log("  获取分享失败"); return@io }
            val msg = if (info.latest <= 0) "没找到能识别集号的视频"
            else buildString {
                append("最新:第 ${info.latest} 集\n")
                append("实有:${info.count} 集(第 ${info.earliest}~${info.latest} 之间)")
                if (info.missing.isNotEmpty()) {
                    append("\n\n缺失 ${info.missing.size} 集(分享里没传):\n")
                    append(Episode.formatMissing(info.missing))
                    append("\n\n(缺集是上传者没传;补传后会自动补上)")
                } else append("\n\n✓ 连续完整,无缺集")
            }
            AlertDialog.Builder(this)
                .setTitle("${t.name} - 集数详情")
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun confirmRemoveTrack(t: TrackItem) {
        AlertDialog.Builder(this)
            .setTitle("移除追更")
            .setMessage("把「${t.name}」从追更清单移除?\n(只是不再自动追,网盘已转存的文件不删)")
            .setPositiveButton("移除") { _, _ ->
                store.removeTrack(t.folderId)
                refreshTrackList()
                log("已移除《${t.name}》")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 定时设置 ----------------

    private fun onSaveTimer() {
        val h = etInterval.text.toString().toIntOrNull()
        if (h == null || h < 1 || h > 24) { toast("请填 1~24 的整数小时"); return }
        store.intervalHours = h
        UpdateWorker.schedule(this, h)
        log("✓ 已开启自动追更:每 $h 小时检查一次")
        log("  App 在后台或关闭也会按时检查(系统调度)")
        toast("已开启,每 $h 小时自动检查")
    }

    // ---------------- 工具 ----------------

    /** 在 IO 线程执行 work,结果回主线程给 done。 */
    private fun <T> io(work: () -> T, done: (T) -> Unit) {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                try { work() } catch (e: Exception) { log("出错:${e.message}"); null }
            }
            @Suppress("UNCHECKED_CAST")
            if (r != null || true) done(r as T)
        }
    }

    private fun log(msg: String) {
        runOnUiThread {
            tvLog.append(msg + "\n")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
