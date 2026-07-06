package com.guoman.tracker.api

/**
 * 集号识别与缺集分析(从桌面版 Python 逻辑翻译)。
 *
 * 支持两种命名:
 *   - SxxExx 美剧式:S01E86.2024.2160p.WEB-DL.mp4 -> 86
 *   - 开头数字式:230 4K.mp4 -> 230
 * 排除:花絮/特辑等非正片;年份数字(1900-2099)不当集号。
 */
object Episode {

    private val NON_EPISODE_KEYWORDS = listOf(
        "剪辑", "花絮", "特辑", "拜年", "精彩", "特别", "番外", "彩蛋",
        "预告", "宣传", "片头", "片尾", "合集", "加更", "抢先", "直拍",
        "幕后", "访谈", "先导", "PV", "MV", "cut", "CUT"
    )

    private val VIDEO_EXTS = listOf(
        ".mp4", ".mkv", ".ts", ".flv", ".avi", ".m4v", ".mov", ".rmvb"
    )

    fun isVideo(name: String): Boolean {
        val low = name.lowercase()
        return VIDEO_EXTS.any { low.endsWith(it) }
    }

    private fun isEpisodeFile(name: String): Boolean {
        return NON_EPISODE_KEYWORDS.none { name.contains(it) }
    }

    /** 提取集号,失败返回 -1。 */
    fun episodeNum(name: String): Int {
        if (!isEpisodeFile(name)) return -1
        // 去扩展名
        var base = name
        val dot = base.lastIndexOf('.')
        if (dot > 0) base = base.substring(0, dot)
        base = base.trim()

        // 1) SxxExx 格式,取 E 后面的数字
        val se = Regex("[Ss]\\d{1,3}[Ee](\\d{1,4})").find(name)
        if (se != null) {
            return se.groupValues[1].toInt()
        }

        // 2) 开头的数字
        val m = Regex("^(\\d{1,4})").find(base) ?: return -1
        val n = m.groupValues[1].toInt()
        // 排除年份
        if (n in 1900..2099) return -1
        return n
    }

    data class Analysis(
        val count: Int,
        val latest: Int,
        val earliest: Int,
        val missing: List<Int>
    )

    fun analyze(names: List<String>): Analysis {
        val nums = names.map { episodeNum(it) }.filter { it > 0 }.toSortedSet().toList()
        if (nums.isEmpty()) return Analysis(0, 0, 0, emptyList())
        val earliest = nums.first()
        val latest = nums.last()
        val have = nums.toSet()
        val missing = (earliest..latest).filter { it !in have }
        return Analysis(nums.size, latest, earliest, missing)
    }

    /** 把缺集列表压成易读区间:[20,21,22,25] -> "20-22, 25" */
    fun formatMissing(missing: List<Int>): String {
        if (missing.isEmpty()) return ""
        val parts = mutableListOf<String>()
        var start = missing[0]
        var prev = missing[0]
        for (i in 1 until missing.size) {
            val n = missing[i]
            if (n == prev + 1) {
                prev = n
            } else {
                parts.add(if (start == prev) "$start" else "$start-$prev")
                start = n
                prev = n
            }
        }
        parts.add(if (start == prev) "$start" else "$start-$prev")
        return parts.joinToString(", ")
    }
}
