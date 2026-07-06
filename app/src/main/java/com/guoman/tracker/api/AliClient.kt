package com.guoman.tracker.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 阿里云盘接口封装(从 Python 的 aligo 库翻译而来)。
 *
 * 只实现追更需要的功能:
 *   - 扫码登录(生成二维码 / 轮询状态 / 拿 refresh_token)
 *   - 用 refresh_token 换 access_token
 *   - 读取分享文件列表
 *   - 转存分享文件到自己网盘
 *   - 创建网盘文件夹
 *
 * 绝不删除网盘任何文件。
 */
class AliClient {

    companion object {
        const val AUTH_HOST = "https://auth.aliyundrive.com"
        const val PASSPORT_HOST = "https://passport.aliyundrive.com"
        const val API_HOST = "https://api.aliyundrive.com"
        const val CLIENT_ID = "25dzX3vbYqktVxyX"

        private val JSON = "application/json".toMediaType()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // 登录后填充
    var accessToken: String = ""
    var refreshToken: String = ""
    var driveId: String = ""
    var userName: String = ""

    // ---------------- 扫码登录 ----------------

    /** 生成二维码。返回 (二维码内容, 用于轮询的参数JSON)。失败返回 null。 */
    fun generateQrCode(): QrSession? {
        val url = "$PASSPORT_HOST/newlogin/qrcode/generate.do?appName=aliyun_drive"
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val data = JSONObject(body).getJSONObject("content").getJSONObject("data")
            val qrLink = data.getString("codeContent")
            // 轮询需要回传这些字段
            val t = data.getString("t")
            val ck = data.getString("ck")
            return QrSession(qrLink, t, ck)
        }
    }

    /**
     * 轮询二维码状态。
     * 返回: "NEW"(未扫) / "SCANED"(已扫待确认) / "CONFIRMED"(已确认,已登录) / "EXPIRED"(过期)
     * CONFIRMED 时会自动完成登录(填充 token)。
     */
    fun queryQrCode(qr: QrSession): String {
        val url = "$PASSPORT_HOST/newlogin/qrcode/query.do?appName=aliyun_drive"
        val form = "t=${qr.t}&ck=${qr.ck}&appName=aliyun_drive"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
        http.newCall(Request.Builder().url(url).post(form).build()).execute().use { resp ->
            val body = resp.body?.string() ?: return "ERROR"
            val data = JSONObject(body).getJSONObject("content").getJSONObject("data")
            val status = data.optString("qrCodeStatus", "EXPIRED")
            if (status == "CONFIRMED") {
                // bizExt 是 base64 编码的登录结果,里面有 refreshToken
                val bizExt = data.getString("bizExt")
                val decoded = String(android.util.Base64.decode(bizExt, android.util.Base64.DEFAULT), Charsets.UTF_8)
                val result = JSONObject(decoded)
                    .getJSONObject("pds_login_result")
                val rt = result.getString("refreshToken")
                refreshFromToken(rt)
            }
            return status
        }
    }

    // ---------------- token ----------------

    /** 用 refresh_token 换取 access_token(登录后 & 每次启动都用)。成功返回 true。 */
    fun refreshFromToken(rt: String): Boolean {
        val url = "$API_HOST/v2/account/token"
        val payload = JSONObject()
            .put("refresh_token", rt)
            .put("grant_type", "refresh_token")
            .toString()
        val req = Request.Builder().url(url)
            .post(payload.toRequestBody(JSON)).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return false
            val body = resp.body?.string() ?: return false
            val j = JSONObject(body)
            accessToken = j.optString("access_token", "")
            refreshToken = j.optString("refresh_token", rt)
            driveId = j.optString("default_drive_id", "")
            userName = j.optString("nick_name", j.optString("user_name", ""))
            return accessToken.isNotEmpty()
        }
    }

    private fun authedPost(path: String, payload: JSONObject, extraHeaders: Map<String, String> = emptyMap()): String? {
        val builder = Request.Builder()
            .url("$API_HOST$path")
            .post(payload.toString().toRequestBody(JSON))
            .header("Authorization", "Bearer $accessToken")
        for ((k, v) in extraHeaders) builder.header(k, v)
        http.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful && resp.code !in listOf(201, 202)) return null
            return body
        }
    }

    // ---------------- 分享 ----------------

    /** 获取 share_token(读分享内容前必须先拿)。 */
    fun getShareToken(shareId: String, sharePwd: String = ""): String? {
        val url = "$API_HOST/v2/share_link/get_share_token"
        val payload = JSONObject().put("share_id", shareId).put("share_pwd", sharePwd)
        val req = Request.Builder().url(url)
            .post(payload.toString().toRequestBody(JSON)).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            return JSONObject(body).optString("share_token", null)
        }
    }

    /** 列出分享里某个文件夹的内容。返回条目列表。 */
    fun listShareFiles(shareId: String, shareToken: String, parentFileId: String = "root"): List<AliFile> {
        val result = mutableListOf<AliFile>()
        var marker: String? = null
        do {
            val payload = JSONObject()
                .put("share_id", shareId)
                .put("parent_file_id", parentFileId)
                .put("limit", 200)
                .put("order_by", "name")
                .put("order_direction", "ASC")
            if (marker != null) payload.put("marker", marker)
            val body = postShare("/adrive/v3/file/list", payload, shareToken) ?: break
            val j = JSONObject(body)
            val items = j.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                result.add(
                    AliFile(
                        fileId = it.optString("file_id"),
                        name = it.optString("name"),
                        type = it.optString("type"),
                        size = it.optLong("size", 0)
                    )
                )
            }
            marker = j.optString("next_marker", "").ifEmpty { null }
        } while (marker != null)
        return result
    }

    private fun postShare(path: String, payload: JSONObject, shareToken: String): String? {
        val builder = Request.Builder()
            .url("$API_HOST$path")
            .post(payload.toString().toRequestBody(JSON))
            .header("Authorization", "Bearer $accessToken")
            .header("x-share-token", shareToken)
        http.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }

    /** 转存分享文件到自己网盘。成功返回 true。 */
    fun saveToDrive(shareId: String, shareToken: String, fileId: String, toParentFileId: String): Boolean {
        val payload = JSONObject()
            .put("share_id", shareId)
            .put("file_id", fileId)
            .put("to_parent_file_id", toParentFileId)
            .put("to_drive_id", driveId)
            .put("auto_rename", true)
        val body = postShare("/v2/file/copy", payload, shareToken)
        return body != null
    }

    // ---------------- 自己网盘 ----------------

    /** 在自己网盘创建文件夹(已存在则返回已有的)。返回 file_id。 */
    fun ensureFolder(name: String, parentFileId: String = "root"): String? {
        val payload = JSONObject()
            .put("drive_id", driveId)
            .put("parent_file_id", parentFileId)
            .put("name", name)
            .put("type", "folder")
            .put("check_name_mode", "refuse")   // 已存在就返回已有的
        val body = authedPost("/adrive/v2/file/createWithFolders", payload) ?: return null
        return JSONObject(body).optString("file_id", null)
    }

    /** 列出自己网盘某文件夹里的文件名(用于判断是否已转存)。 */
    fun listMyFiles(parentFileId: String): List<AliFile> {
        val result = mutableListOf<AliFile>()
        var marker: String? = null
        do {
            val payload = JSONObject()
                .put("drive_id", driveId)
                .put("parent_file_id", parentFileId)
                .put("limit", 200)
            if (marker != null) payload.put("marker", marker)
            val body = authedPost("/adrive/v3/file/list", payload) ?: break
            val j = JSONObject(body)
            val items = j.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                result.add(
                    AliFile(
                        fileId = it.optString("file_id"),
                        name = it.optString("name"),
                        type = it.optString("type"),
                        size = it.optLong("size", 0)
                    )
                )
            }
            marker = j.optString("next_marker", "").ifEmpty { null }
        } while (marker != null)
        return result
    }
}

data class QrSession(val qrLink: String, val t: String, val ck: String)

data class AliFile(
    val fileId: String,
    val name: String,
    val type: String,
    val size: Long
) {
    val isFolder: Boolean get() = type == "folder"
}
