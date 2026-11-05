package com.example.audiobookplayer.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.example.audiobookplayer.databinding.ActivityPlayerBinding
import com.example.audiobookplayer.player.AudioPlaybackService

class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private val handler = Handler(Looper.getMainLooper())
    private var isTrackingSeekBar = false

    private val playbackService: AudioPlaybackService?
        get() = AudioPlaybackService.instance

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            if (!isTrackingSeekBar) {
                val current = AudioPlaybackService.currentPosition()
                binding.seekBar.progress = current.toInt()
                binding.tvCurrentTime.text = formatTime(current)
            }
            binding.btnPlayPause.isSelected = AudioPlaybackService.isPlaying()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "正在播放"

        // 确保播放服务已启动
        startService(Intent(this, AudioPlaybackService::class.java))

        setupControls()
        setupSeekBar()
        setupSpeedControl()
    }

    private fun setupControls() {
        binding.btnPlayPause.setOnClickListener {
            playbackService?.togglePlayPause()
        }

        binding.btnPrevious.setOnClickListener {
            playbackService?.playPrevious()
        }

        binding.btnNext.setOnClickListener {
            playbackService?.playNext()
        }
    }

    private fun setupSeekBar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvCurrentTime.text = formatTime(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isTrackingSeekBar = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isTrackingSeekBar = false
                seekBar?.let {
                    playbackService?.seekTo(it.progress.toLong())
                }
            }
        })
    }

    private fun setupSpeedControl() {
        val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f)
        var currentSpeedIndex = 2 // 默认1.0x
        binding.btnSpeed.text = "${speeds[currentSpeedIndex]}x"

        binding.btnSpeed.setOnClickListener {
            currentSpeedIndex = (currentSpeedIndex + 1) % speeds.size
            val speed = speeds[currentSpeedIndex]
            playbackService?.setSpeed(speed)
            binding.btnSpeed.text = "${speed}x"
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    override fun onStart() {
        super.onStart()
        handler.post(updateProgressRunnable)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(updateProgressRunnable)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
