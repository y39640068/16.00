package com.example.audiobookplayer.player

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import com.google.common.util.concurrent.ListenableFuture
import com.example.audiobookplayer.ui.MainActivity
import com.example.audiobookplayer.model.AudioBook
import com.example.audiobookplayer.model.Chapter

class AudioPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var currentBook: AudioBook? = null
    private var currentChapterIndex: Int = 0

    companion object {
        const val ACTION_PLAY_BOOK = "com.example.audiobookplayer.PLAY_BOOK"
        const val EXTRA_CHAPTER_INDEX = "chapter_index"
        var instance: AudioPlaybackService? = null
            private set

        /** 待播放的书（由 Activity 设置后通过 startService 触发播放） */
        @Volatile
        var pendingBook: AudioBook? = null

        @Volatile
        var pendingChapterIndex: Int = 0

        fun currentPosition(): Long {
            return instance?.player?.currentPosition ?: 0L
        }

        fun currentChapter(): Int {
            return instance?.currentChapterIndex ?: 0
        }

        fun isPlaying(): Boolean {
            return instance?.player?.isPlaying ?: false
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    playNext()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // 播放状态变化
            }
        })

        val sessionIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, sessionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .setCallback(MediaSessionCallback())
            .build()

        // 若启动服务时带有待播放的书，立即开始播放
        pendingBook?.let { book ->
            pendingBook = null
            playBook(book, pendingChapterIndex)
        }
    }

    fun playBook(book: AudioBook, chapterIndex: Int = 0) {
        currentBook = book
        currentChapterIndex = chapterIndex
        playChapter(chapterIndex)
    }

    fun playOnlineChapter(chapter: Chapter, bookTitle: String) {
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(chapter.url))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(chapter.title)
                    .setArtist(bookTitle)
                    .build()
            )
            .build()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
    }

    fun playChapter(index: Int) {
        val book = currentBook ?: return
        if (index < 0 || index >= book.chapters.size) return
        currentChapterIndex = index
        val chapter = book.chapters[index]

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(chapter.path))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(chapter.title)
                    .setArtist(book.title)
                    .build()
            )
            .build()

        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
    }

    fun playNext() {
        currentBook?.let {
            if (currentChapterIndex < it.chapters.size - 1) {
                playChapter(currentChapterIndex + 1)
            }
        }
    }

    fun playPrevious() {
        currentBook?.let {
            if (currentChapterIndex > 0) {
                playChapter(currentChapterIndex - 1)
            }
        }
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        instance = null
        super.onDestroy()
    }

    private inner class MediaSessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    SessionCommands.Builder()
                        .addAllPredefinedCommands()
                        .build()
                )
                .setAvailablePlayerCommands(
                    PlayerCommands.Builder()
                        .addAllPredefinedCommands()
                        .build()
                )
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }
}
