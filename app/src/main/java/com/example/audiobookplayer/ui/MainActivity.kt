package com.example.audiobookplayer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.audiobookplayer.R
import com.example.audiobookplayer.databinding.ActivityMainBinding
import com.example.audiobookplayer.model.AudioBook
import com.example.audiobookplayer.parser.SstsPluginEngine
import com.example.audiobookplayer.player.AudioPlaybackService
import com.example.audiobookplayer.util.LocalAudioScanner
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var bookAdapter: BookShelfAdapter
    private var currentTab = 0 // 0:书架 1:发现 2:搜索
    private var allLocalBooks = emptyList<AudioBook>()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            scanLocalBooks()
        }
    }

    private val openFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            scanSelectedFolder(it)
        }
    }

    private val importPluginLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importSstsPlugin(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupRecyclerView()
        setupTabs()
        checkPermissions()

        // 初始化ssts引擎
        SstsPluginEngine.getInstance(this)
    }

    private fun setupRecyclerView() {
        bookAdapter = BookShelfAdapter { book -> openBookDetail(book) }
        binding.recyclerView.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2)
            adapter = bookAdapter
        }
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tab?.let {
                    currentTab = it.position
                    when (it.position) {
                        0 -> { /* 书架 */
                            bookAdapter.submitList(allLocalBooks)
                            supportActionBar?.title = "我的书架"
                        }
                        1 -> { /* 插件 */
                            openPluginManage()
                        }
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val needRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needRequest.isEmpty()) {
            scanLocalBooks()
        } else {
            requestPermissionLauncher.launch(needRequest.toTypedArray())
        }
    }

    private fun scanLocalBooks() {
        lifecycleScope.launch {
            val books = withContext(Dispatchers.IO) {
                LocalAudioScanner.scanAllAudio(this@MainActivity)
            }
            allLocalBooks = books
            if (currentTab == 0) {
                bookAdapter.submitList(books)
            }
        }
    }

    private fun scanSelectedFolder(treeUri: Uri) {
        lifecycleScope.launch {
            val folderDoc = DocumentFile.fromTreeUri(this@MainActivity, treeUri) ?: return@launch
            val folderPath = "${Environment.getExternalStorageDirectory().absolutePath}/${folderDoc.name}"
            val book = withContext(Dispatchers.IO) {
                LocalAudioScanner.scanFolder(folderPath)
            }
            book?.let {
                openBookDetail(it)
            }
        }
    }

    private fun openBookDetail(book: AudioBook) {
        val intent = Intent(this, BookDetailActivity::class.java).apply {
            putExtra("book_title", book.title)
            putExtra("book_folder", book.folderPath)
            putExtra("chapter_count", book.chapters.size)
        }
        startActivity(intent)
    }

    private fun openPluginManage() {
        val intent = Intent(this, PluginManageActivity::class.java)
        startActivity(intent)
    }

    private fun importSstsPlugin(uri: Uri) {
        lifecycleScope.launch {
            val tempFile = File(cacheDir, "plugin_import.zip")
            withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            val success = SstsPluginEngine.getInstance(this@MainActivity).importPluginFromZip(tempFile)
            tempFile.delete()

            if (success) {
                android.widget.Toast.makeText(this@MainActivity, "插件导入成功！", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(this@MainActivity, "插件导入失败", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_scan_folder -> {
                openFolderLauncher.launch(null)
                true
            }
            R.id.action_import_plugin -> {
                importPluginLauncher.launch(arrayOf("application/zip"))
                true
            }
            R.id.action_refresh -> {
                scanLocalBooks()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        if (currentTab == 0) {
            bookAdapter.submitList(allLocalBooks)
        }
    }
}
