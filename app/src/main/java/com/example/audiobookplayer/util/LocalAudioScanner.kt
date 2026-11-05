package com.example.audiobookplayer.util

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.example.audiobookplayer.model.AudioBook
import com.example.audiobookplayer.model.AudioChapter
import com.example.audiobookplayer.model.SourceType
import java.io.File

/**
 * 本地音频扫描器
 * 自动扫描设备中的音频文件，按文件夹分组为有声书并自然排序
 */
object LocalAudioScanner {

    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "flac", "wav", "ogg", "aac", "wma", "ape")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "bmp", "webp")

    /**
     * 扫描设备所有音频，按文件夹分组
     */
    fun scanAllAudio(context: Context): List<AudioBook> {
        val folderMap = mutableMapOf<String, MutableList<AudioFileInfo>>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ARTIST
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.IS_PODCAST} != 0"
        val sortOrder = "${MediaStore.Audio.Media.DATA} ASC"

        context.contentResolver.query(
            collection,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)

            while (cursor.moveToNext()) {
                val path = cursor.getString(dataColumn)
                val file = File(path)
                if (!file.exists() || !isAudioFile(file.name)) continue

                val folder = file.parentFile ?: continue
                val folderPath = folder.absolutePath
                val title = cursor.getString(titleColumn) ?: file.nameWithoutExtension
                val duration = cursor.getLong(durationColumn)
                val size = cursor.getLong(sizeColumn)
                val album = cursor.getString(albumColumn)
                val artist = cursor.getString(artistColumn)

                val audioList = folderMap.getOrPut(folderPath) { mutableListOf() }
                audioList.add(AudioFileInfo(
                    path = path,
                    fileName = file.name,
                    title = title,
                    duration = duration,
                    size = size,
                    album = album,
                    artist = artist
                ))
            }
        }

        // 转换为AudioBook列表
        return folderMap.mapNotNull { (folderPath, audioFiles) ->
            if (audioFiles.isEmpty()) return@mapNotNull null

            val folder = File(folderPath)
            val bookTitle = folder.name

            // 查找封面
            val coverPath = findCoverInFolder(folder)

            // 自然排序
            val sortedFiles = audioFiles.sortedWith(compareBy(NaturalOrderComparator.INSTANCE) { it.fileName })

            // 转换为章节列表
            val chapters = sortedFiles.mapIndexed { index, audio ->
                AudioChapter(
                    index = index,
                    title = audio.title.ifEmpty { audio.fileName.substringBeforeLast('.') },
                    path = audio.path,
                    duration = audio.duration,
                    isLocal = true
                )
            }

            AudioBook(
                title = bookTitle,
                folderPath = folderPath,
                coverPath = coverPath,
                author = sortedFiles.firstOrNull()?.artist,
                artist = sortedFiles.firstOrNull()?.artist,
                chapters = chapters,
                sourceType = SourceType.LOCAL
            )
        }.sortedByDescending { it.addTime }
    }

    /**
     * 扫描指定文件夹
     */
    fun scanFolder(folderPath: String): AudioBook? {
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) return null

        val audioFiles = folder.listFiles()
            ?.filter { it.isFile && isAudioFile(it.name) }
            ?.toMutableList() ?: mutableListOf()

        // 递归扫描子文件夹？不，一个子文件夹就是另一本书
        // 但如果当前文件夹没有音频，就递归找子文件夹
        if (audioFiles.isEmpty()) {
            val books = mutableListOf<AudioBook>()
            folder.listFiles()?.filter { it.isDirectory }?.forEach { subFolder ->
                scanFolder(subFolder.absolutePath)?.let { books.add(it) }
            }
            return null
        }

        val bookTitle = folder.name
        val coverPath = findCoverInFolder(folder)

        // 自然排序
        val sortedFiles = audioFiles.sortedWith(compareBy(NaturalOrderComparator.INSTANCE) { it.name })

        val chapters = sortedFiles.mapIndexed { index, file ->
            AudioChapter(
                index = index,
                title = file.nameWithoutExtension,
                path = file.absolutePath,
                isLocal = true
            )
        }

        return AudioBook(
            title = bookTitle,
            folderPath = folderPath,
            coverPath = coverPath,
            chapters = chapters,
            sourceType = SourceType.LOCAL
        )
    }

    private fun isAudioFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    private fun isImageFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTENSIONS
    }

    /**
     * 查找文件夹中的封面图片
     */
    private fun findCoverInFolder(folder: File): String? {
        // 优先查找名字包含cover、folder、front、封面的图片
        val files = folder.listFiles() ?: return null
        val images = files.filter { it.isFile && isImageFile(it.name) }

        // 优先匹配常见封面文件名
        images.firstOrNull {
            val name = it.name.lowercase()
            name.contains("cover") || name.contains("folder") || name.contains("front") ||
                    name.contains("封面") || name.contains("海报")
        }?.let { return it.absolutePath }

        // 否则取第一张图片
        return images.firstOrNull()?.absolutePath
    }

    private data class AudioFileInfo(
        val path: String,
        val fileName: String,
        val title: String,
        val duration: Long,
        val size: Long,
        val album: String?,
        val artist: String?
    )
}
