package com.example.audiobookplayer.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.audiobookplayer.databinding.ActivityBookDetailBinding
import com.example.audiobookplayer.model.AudioBook
import com.example.audiobookplayer.player.AudioPlaybackService
import com.example.audiobookplayer.util.LocalAudioScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookDetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBookDetailBinding
    private lateinit var chapterAdapter: ChapterListAdapter
    private var book: AudioBook? = null

    private val playbackService: AudioPlaybackService?
        get() = AudioPlaybackService.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val folderPath = intent.getStringExtra("book_folder") ?: return
        val bookTitle = intent.getStringExtra("book_title") ?: ""
        supportActionBar?.title = bookTitle

        setupRecyclerView()
        loadBook(folderPath)

        binding.fabPlayAll.setOnClickListener {
            book?.let { b -> play(b, 0) }
        }
    }

    private fun setupRecyclerView() {
        chapterAdapter = ChapterListAdapter { index ->
            book?.let { b -> play(b, index) }
        }
        binding.recyclerViewChapters.apply {
            layoutManager = LinearLayoutManager(this@BookDetailActivity)
            adapter = chapterAdapter
        }
    }

    private fun loadBook(folderPath: String) {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val loadedBook = withContext(Dispatchers.IO) {
                LocalAudioScanner.scanFolder(folderPath)
            }
            binding.progressBar.visibility = View.GONE

            loadedBook?.let {
                book = it
                binding.tvBookTitle.text = it.title
                binding.tvChapterCount.text = "共 ${it.chapters.size} 集"
                chapterAdapter.submitList(it.chapters)
            }
        }
    }

    /**
     * 播放指定章节。若服务已存在则直接调用，否则通过 startService 通知服务播放。
     */
    private fun play(b: AudioBook, index: Int) {
        val service = playbackService
        if (service != null) {
            service.playBook(b, index)
        } else {
            AudioPlaybackService.pendingBook = b
            AudioPlaybackService.pendingChapterIndex = index
            startService(Intent(this, AudioPlaybackService::class.java).apply {
                action = AudioPlaybackService.ACTION_PLAY_BOOK
            })
        }
        openPlayer()
    }

    private fun openPlayer() {
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
