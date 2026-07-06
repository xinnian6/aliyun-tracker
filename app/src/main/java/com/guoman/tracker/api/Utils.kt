package com.guoman.tracker.api

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** 小工具:二维码生成、分享链接解析。 */
object Utils {
    private val VIDEO_EXTS = listOf(".mp4", ".mkv", ".ts", ".flv", ".avi", ".m4v", ".mov", ".rmvb")

    /** 把文本生成二维码 Bitmap。 */
    fun makeQr(text: String, size: Int = 480): Bitmap {
        val writer = QRCodeWriter()
        val matrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    /**
     * 从分享链接解析出 (share_id, folder_id)。
     * 支持:
     *   https://www.alipan.com/s/abc123
     *   https://www.alipan.com/s/abc123/folder/xxxx
     *   https://www.aliyundrive.com/s/abc123
     * 也支持直接粘 share_id。
     */
    fun parseShareLink(link: String): Pair<String, String> {
        val trimmed = link.trim()
        var shareId = ""
        var folderId = "root"
        Regex("/s/([A-Za-z0-9]+)").find(trimmed)?.let { shareId = it.groupValues[1] }
        Regex("/folder/([A-Za-z0-9]+)").find(trimmed)?.let { folderId = it.groupValues[1] }
        if (shareId.isEmpty() && Regex("^[A-Za-z0-9]+$").matches(trimmed)) {
            shareId = trimmed
        }
        return Pair(shareId, folderId)
    }


    fun isVideo(name: String): Boolean {
        val low = name.lowercase()
        return VIDEO_EXTS.any { low.endsWith(it) }
    }
}
