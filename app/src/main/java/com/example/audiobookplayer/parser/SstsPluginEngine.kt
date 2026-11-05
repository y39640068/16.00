package com.example.audiobookplayer.parser

import android.content.Context as AppContext
import android.util.Log
import com.example.audiobookplayer.model.*
import com.example.audiobookplayer.util.NaturalOrderComparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.javascript.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * SSTS 书源插件运行时
 * 完全兼容 ssts-Android 书源规范，支持 registerSource() 注册的 JS 插件
 */
class SstsPluginEngine private constructor(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val rhino: Context = Context.enter()
    private val scope: Scriptable = rhino.initStandardObjects()
    private val loadedPlugins = mutableListOf<SstsPlugin>()
    private val registeredSources = mutableListOf<SstsSource>()
    private val pluginDir = File(context.filesDir, "ssts_plugins")

    init {
        rhino.optimizationLevel = -1
        rhino.languageVersion = Context.VERSION_ES6
        initShunApi()
        if (!pluginDir.exists()) pluginDir.mkdirs()
    }

    companion object {
        private const val TAG = "SstsPluginEngine"

        @Volatile
        private var instance: SstsPluginEngine? = null

        fun getInstance(context: Context): SstsPluginEngine {
            return instance ?: synchronized(this) {
                instance ?: SstsPluginEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * 初始化 Shun.* API，完全匹配ssts的JS接口
     */
    private fun initShunApi() {
        val shunObj = rhino.newObject(scope)

        // Shun.enc(urlEncode)
        ScriptableObject.putProperty(shunObj, "enc", object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any>): Any {
                val str = if (args.isNotEmpty()) Context.toString(args[0]) else ""
                return java.net.URLEncoder.encode(str, "UTF-8")
            }
        })

        // Shun.unescape
        ScriptableObject.putProperty(shunObj, "unescape", object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any>): Any {
                val str = if (args.isNotEmpty()) Context.toString(args[0]) else ""
                return java.net.URLDecoder.decode(str, "UTF-8").replace("\\/", "/")
            }
        })

        // Shun.coverReferer
        ScriptableObject.putProperty(shunObj, "coverReferer", object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any>): Any {
                val domain = if (args.size > 0) Context.toString(args[0]) else ""
                val referer = if (args.size > 1) Context.toString(args[1]) else ""
                val obj = cx.newObject(scope)
                ScriptableObject.putProperty(obj, "Referer", referer)
                ScriptableObject.putProperty(obj, "domain", domain)
                return obj
            }
        })

        // Shun.nth - 取第n个元素
        ScriptableObject.putProperty(shunObj, "nth", object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any>): Any {
                if (args.size < 2) return cx.newObject(scope)
                val list = args[0] as? NativeArray ?: return cx.newObject(scope)
                val idx = Context.toNumber(args[1]).toInt()
                if (idx < 0 || idx >= list.length) return cx.newObject(scope)
                return list[idx] ?: cx.newObject(scope)
            }
        })

        // Shun.getDoc - 获取HTML并解析为Document
        val getDocFunc = object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any>): Any {
                val host = args[0] as? NativeObject ?: return cx.newObject(scope)
                val url = if (args.size > 1) Context.toString(args[1]) else ""
                val useCache = if (args.size > 2) Context.toBoolean(args[2]) else false

                val html = httpGet(url, getHeaders(host))
                return wrapJsoupDocument(cx, scope, Jsoup.parse(html, url))
            }
        }
        ScriptableObject.putProperty(shunObj, "getDoc", getDocFunc)

        // Shun.getJson - 获取JSON
        val getJsonFunc = object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any>): Any {
                val host = args[0] as? NativeObject ?: return cx.newObject(scope)
                val url = if (args.size > 1) Context.toString(args[1]) else ""
                val jsonStr = httpGet(url, getHeaders(host))
                return rhino.evaluateString(scope, "($jsonStr)", "json", 1, null)
            }
        }
        ScriptableObject.putProperty(shunObj, "getJson", getJsonFunc)

        ScriptableObject.putProperty(scope, "Shun", shunObj)

        // 全局str函数 - 取JSON路径
        ScriptableObject.putProperty(scope, "str", object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any>): Any {
                if (args.size < 2) return ""
                var obj = args[0]
                val path = Context.toString(args[1]).split(".")
                for (key in path) {
                    if (obj is NativeObject) {
                        obj = ScriptableObject.getProperty(obj, key) ?: return ""
                    } else if (obj is NativeArray) {
                        val idx = key.toIntOrNull() ?: return ""
                        obj = obj[idx] ?: return ""
                    } else {
                        return ""
                    }
                }
                return Context.toString(obj)
            }
        })

        // 全局 registerSource 函数 - ssts书源注册入口
        ScriptableObject.putProperty(scope, "registerSource", object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any>): Any {
                if (args.isEmpty()) return Undefined.instance
                val srcObj = args[0] as? NativeObject ?: return Undefined.instance

                val source = SstsSource(
                    id = getPropString(srcObj, "id"),
                    name = getPropString(srcObj, "name"),
                    url = getPropString(srcObj, "url"),
                    description = getPropString(srcObj, "description"),
                    jsObject = srcObj
                )
                registeredSources.add(source)
                Log.d(TAG, "已注册书源: ${source.name}")
                return Undefined.instance
            }
        })
    }

    private fun getHeaders(hostObj: NativeObject): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        )
        return headers
    }

    private fun getPropString(obj: NativeObject, key: String): String {
        val v = ScriptableObject.getProperty(obj, key)
        return if (v == null || v is Undefined) "" else Context.toString(v)
    }

    /**
     * 包装Jsoup Document为JS对象，提供select、first、absUrl等方法
     */
    private fun wrapJsoupDocument(cx: Context, scope: Scriptable, doc: Document): Scriptable {
        val obj = cx.newObject(scope)

        // select 方法
        ScriptableObject.putProperty(obj, "select", object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any>): Any {
                val selector = if (args.isNotEmpty()) Context.toString(args[0]) else ""
                val elements = doc.select(selector)
                return wrapElements(cx, scope, elements)
            }
        })

        // first 方法
        ScriptableObject.putProperty(obj, "first", object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any>): Any {
                val selector = if (args.isNotEmpty()) Context.toString(args[0]) else ""
                val el = doc.selectFirst(selector) ?: return cx.newObject(scope)
                return wrapElement(cx, scope, el)
            }
        })

        // text 方法
        ScriptableObject.putProperty(obj, "text", object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any>): Any = doc.text()
        })

        return obj
    }

    private fun wrapElements(cx: Context, scope: Scriptable, elements: org.jsoup.select.Elements): NativeArray {
        val wrapped = elements.map { wrapElement(cx, scope, it) }.toTypedArray()
        return cx.newArray(scope, wrapped) as NativeArray
    }

    /**
     * 包装Jsoup Element为JS对象，模拟ssts中的元素API
     */
    private fun wrapElement(cx: Context, scope: Scriptable, el: Element): Scriptable {
        val obj = cx.newObject(scope)

        // select 方法
        ScriptableObject.putProperty(obj, "select", object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any>): Any {
                val selector = if (args.isNotEmpty()) Context.toString(args[0]) else ""
                return wrapElements(cx, scope, el.select(selector))
            }
        })

        // first 方法
        ScriptableObject.putProperty(obj, "first", object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any>): Any {
                val selector = if (args.isNotEmpty()) Context.toString(args[0]) else ""
                val child = el.selectFirst(selector) ?: return cx.newObject(scope)
                return wrapElement(cx, scope, child)
            }
        })

        // text 方法
        ScriptableObject.putProperty(obj, "text", object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any>): Any = el.text()
        })

        // ownText 方法
        ScriptableObject.putProperty(obj, "ownText", object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any>): Any = el.ownText()
        })

        // absUrl 方法
        ScriptableObject.putProperty(obj, "absUrl", object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any>): Any {
                val attr = if (args.isNotEmpty()) Context.toString(args[0]) else "href"
                return el.absUrl(attr)
            }
        })

        // attr 方法
        ScriptableObject.putProperty(obj, "attr", object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any>): Any {
                val attr = if (args.isNotEmpty()) Context.toString(args[0]) else ""
                return el.attr(attr)
            }
        })

        return obj
    }

    /**
     * 给Host对象加getString/getJson方法
     */
    private fun wrapHostForSource(cx: Context, scope: Scriptable, source: SstsSource): Scriptable {
        val host = cx.newObject(scope)
        val headers = getHeaders(NativeObject())

        // getString
        ScriptableObject.putProperty(host, "getString", object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any>): Any {
                val url = if (args.isNotEmpty()) Context.toString(args[0]) else ""
                return httpGet(url, headers)
            }
        })

        // getJson
        ScriptableObject.putProperty(host, "getJson", object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any>): Any {
                val url = if (args.isNotEmpty()) Context.toString(args[0]) else ""
                val jsonStr = httpGet(url, headers)
                return rhino.evaluateString(scope, "($jsonStr)", "json", 1, null)
            }
        })

        // sniff 嗅探方法兜底
        ScriptableObject.putProperty(host, "sniff", object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any>): Any {
                throw Exception("嗅探暂未实现")
            }
        })

        ScriptableObject.putProperty(host, "url", source.url)
        return host
    }

    private fun httpGet(url: String, headers: Map<String, String>): String {
        val request = Request.Builder()
            .url(url)
            .headers(okhttp3.Headers.of(headers))
            .build()

        client.newCall(request).execute().use { response: Response ->
            if (!response.isSuccessful) throw IOException("HTTP请求失败: $url, code=${response.code}")
            return response.body?.string() ?: ""
        }
    }

    /**
     * 从ZIP文件导入ssts插件
     */
    suspend fun importPluginFromZip(zipFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            registeredSources.clear()
            val pluginName = zipFile.nameWithoutExtension
            val pluginOutDir = File(pluginDir, pluginName)
            if (pluginOutDir.exists()) pluginOutDir.deleteRecursively()
            pluginOutDir.mkdirs()

            // 解压ZIP
            ZipInputStream(zipFile.inputStream()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val outFile = File(pluginOutDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                }
            }

            // 读取manifest.json
            val manifestFile = File(pluginOutDir, "manifest.json")
            if (!manifestFile.exists()) {
                Log.e(TAG, "插件缺少manifest.json")
                return@withContext false
            }

            val manifest = com.google.gson.Gson().fromJson(manifestFile.readText(), SstsManifest::class.java)

            // 按顺序执行所有JS脚本
            for (scriptName in manifest.scripts) {
                val scriptFile = File(pluginOutDir, scriptName)
                if (scriptFile.exists()) {
                    rhino.evaluateString(scope, scriptFile.readText(), scriptName, 1, null)
                }
            }

            loadedPlugins.add(SstsPlugin(
                name = manifest.name,
                version = manifest.version,
                author = manifest.author,
                description = manifest.description,
                dir = pluginOutDir,
                sources = registeredSources.toList()
            ))

            Log.d(TAG, "成功导入插件 ${manifest.name} v${manifest.version}, 共${registeredSources.size}个书源")
            true
        } catch (e: Exception) {
            Log.e(TAG, "导入插件失败", e)
            false
        }
    }

    /**
     * 获取所有已加载的书源
     */
    fun getAllSources(): List<AudioBookSource> {
        return registeredSources.map { src ->
            AudioBookSource(
                id = src.id,
                name = src.name,
                url = src.url,
                description = src.description,
                type = SourceType.SSTS
            )
        }
    }

    /**
     * 搜索书籍
     */
    suspend fun search(sourceId: String, keywords: String, page: Int = 1): SearchResult = withContext(Dispatchers.IO) {
        val source = registeredSources.find { it.id == sourceId } ?: throw Exception("书源不存在")
        val searchFunc = ScriptableObject.getProperty(source.jsObject, "search") as? BaseFunction
            ?: throw Exception("书源不支持搜索")

        val host = wrapHostForSource(rhino, scope, source)
        ScriptableObject.putProperty(source.jsObject, "host", host)

        val result = searchFunc.call(rhino, scope, source.jsObject, arrayOf(keywords, page)) as? NativeObject
            ?: return@withContext SearchResult(emptyList(), page)

        val booksArr = ScriptableObject.getProperty(result, "books") as? NativeArray ?: NativeArray(emptyArray())
        val books = (0 until booksArr.length).mapNotNull { i ->
            val bookObj = booksArr[i] as? NativeObject ?: return@mapNotNull null
            SearchBookItem(
                title = getPropString(bookObj, "title"),
                author = getPropString(bookObj, "author"),
                artist = getPropString(bookObj, "artist"),
                coverUrl = getPropString(bookObj, "coverUrl"),
                bookUrl = getPropString(bookObj, "bookUrl"),
                intro = getPropString(bookObj, "intro"),
                sourceId = source.id,
                sourceName = source.name
            )
        }

        val totalPage = (ScriptableObject.getProperty(result, "totalPage") as? Number)?.toInt() ?: page
        SearchResult(books, totalPage)
    }

    /**
     * 获取书籍详情和章节列表
     */
    suspend fun getBookDetail(sourceId: String, bookUrl: String, loadEpisodes: Boolean = true): BookDetail = withContext(Dispatchers.IO) {
        val source = registeredSources.find { it.id == sourceId } ?: throw Exception("书源不存在")
        val detailFunc = ScriptableObject.getProperty(source.jsObject, "bookDetail") as? BaseFunction
            ?: throw Exception("书源不支持获取详情")

        val host = wrapHostForSource(rhino, scope, source)
        ScriptableObject.putProperty(source.jsObject, "host", host)

        val result = detailFunc.call(rhino, scope, source.jsObject, arrayOf(bookUrl, loadEpisodes)) as? NativeObject
            ?: return@withContext BookDetail(emptyList())

        val episodesArr = ScriptableObject.getProperty(result, "episodes") as? NativeArray ?: NativeArray(emptyArray())
        val episodes = (0 until episodesArr.length).mapNotNull { i ->
            val epObj = episodesArr[i] as? NativeObject ?: return@mapNotNull null
            Chapter(
                title = getPropString(epObj, "title"),
                url = getPropString(epObj, "url")
            )
        }

        BookDetail(
            episodes = episodes,
            title = getPropString(result, "title"),
            author = getPropString(result, "author"),
            artist = getPropString(result, "artist"),
            intro = getPropString(result, "intro"),
            coverUrl = getPropString(result, "coverUrl"),
            status = getPropString(result, "status")
        )
    }

    /**
     * 获取章节音频地址
     */
    suspend fun getAudioUrl(sourceId: String, chapterUrl: String): String = withContext(Dispatchers.IO) {
        val source = registeredSources.find { it.id == sourceId } ?: throw Exception("书源不存在")
        val audioFunc = ScriptableObject.getProperty(source.jsObject, "audio") as? BaseFunction
            ?: throw Exception("书源不支持解析音频")

        val host = wrapHostForSource(rhino, scope, source)
        ScriptableObject.putProperty(source.jsObject, "host", host)

        val result = audioFunc.call(rhino, scope, source.jsObject, arrayOf(chapterUrl))
        return@withContext Context.toString(result)
    }
}

data class SstsManifest(
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val scripts: List<String>
)

data class SstsPlugin(
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val dir: File,
    val sources: List<SstsSource>
)

data class SstsSource(
    val id: String,
    val name: String,
    val url: String,
    val description: String,
    val jsObject: NativeObject
)
