package com.example.audiobookplayer.model

/**
 * 音频文件条目，本地扫描结果
 */
data class AudioFile(
    val path: String,
    val title: String,
    val duration: Long = 0,
    val size: Long = 0,
    val bookName: String = "",
    val chapterIndex: Int = 0
)

/**
 * 本地有声书，对应一个文件夹
 */
data class AudioBook(
    val id: Long = 0,
    val title: String,
    val folderPath: String,
    val coverPath: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val chapters: List<AudioChapter>,
    val lastPlayPosition: Long = 0,
    val lastPlayChapter: Int = 0,
    val addTime: Long = System.currentTimeMillis(),
    val sourceType: SourceType = SourceType.LOCAL
) {
    val chapterCount get() = chapters.size
}

/**
 * 有声书章节
 */
data class AudioChapter(
    val index: Int,
    val title: String,
    val path: String,
    val duration: Long = 0,
    val isLocal: Boolean = true
)

/**
 * 书源类型
 */
enum class SourceType {
    LOCAL,  // 本地文件
    SSTS    // ssts-Android插件书源
}

/**
 * 在线书源
 */
data class AudioBookSource(
    val id: String,
    val name: String,
    val url: String,
    val description: String,
    val type: SourceType,
    val enabled: Boolean = true
)

/**
 * 搜索结果
 */
data class SearchResult(
    val books: List<SearchBookItem>,
    val totalPage: Int
)

/**
 * 搜索到的书籍项
 */
data class SearchBookItem(
    val title: String,
    val author: String = "",
    val artist: String = "",
    val coverUrl: String = "",
    val bookUrl: String,
    val intro: String = "",
    val sourceId: String,
    val sourceName: String
)

/**
 * 书籍详情+章节列表
 */
data class BookDetail(
    val episodes: List<Chapter>,
    val title: String = "",
    val author: String = "",
    val artist: String = "",
    val intro: String = "",
    val coverUrl: String = "",
    val status: String = ""
)

/**
 * 在线章节
 */
data class Chapter(
    val title: String,
    val url: String
)
