package com.offlines.download

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.offlines.model.MapFile
import com.offlines.model.MapSeries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class MapDownloader(private val storageDir: File) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    companion object {
        const val FDROID_API_URL = "https://f-droid.org/api/v1/packages/app.comaps.fdroid"
        const val FDROID_APK_BASE = "https://f-droid.org/repo"
        const val COMAPS_APK_FILENAME = "comaps.apk"
        const val COMAPS_VERSION_FILENAME = "comaps_version.txt"
    }

    private val gson = Gson()
    private val cdnBase = "https://cdn-us-1.comaps.app"

    private var cachedSeries: List<MapSeries>? = null
    private var cachedCountries: MutableMap<Int, List<MapFile>> = mutableMapOf()
    private var cachedServers: MutableMap<Int, String> = mutableMapOf()

    suspend fun fetchMapSeries(): List<MapSeries> = withContext(Dispatchers.IO) {
        cachedSeries?.let { return@withContext it }
        val text = fetchString("$cdnBase/meta/maps.json")
        val json = gson.fromJson(text, JsonElement::class.java)
        val series = json.asJsonObject.getAsJsonObject("map-series")
        val result = series.keySet().sortedDescending().map { key ->
            val obj = series.getAsJsonObject(key)
            MapSeries(key, obj.get("latest").asInt)
        }
        cachedSeries = result
        result
    }

    suspend fun fetchCountries(series: MapSeries): List<MapFile> = withContext(Dispatchers.IO) {
        cachedCountries[series.version]?.let { return@withContext it }
        val server = getBestServer(series.version)
        val base = server.trimEnd('/')
        val text = fetchString("$base/maps/${series.name}/${series.version}/countries.txt")
        val root = gson.fromJson(text, JsonElement::class.java)
        val names = mutableSetOf<String>()
        collectIds(root.asJsonObject, names)
        val result = names.map { MapFile(id = it, country = it.split("_").first()) }
            .distinctBy { it.id }
        cachedCountries[series.version] = result
        result
    }

    private fun collectIds(obj: JsonElement, acc: MutableSet<String>) {
        val g = obj.asJsonObject.getAsJsonArray("g") ?: return
        for (element in g) {
            val child = element.asJsonObject
            if (child.has("g")) {
                collectIds(child, acc)
            } else {
                val id = child.get("id")?.asString
                if (!id.isNullOrBlank()) acc.add(id)
            }
        }
    }

    suspend fun getBestServer(version: Int): String = withContext(Dispatchers.IO) {
        cachedServers[version]?.let { return@withContext it }
        val text = fetchString("$cdnBase/servers?version=$version")
        val json = gson.fromJson(text, JsonElement::class.java)
        val result = json.asJsonArray.first().asString
        cachedServers[version] = result
        result
    }

    suspend fun downloadMeta(series: MapSeries, baseUrl: String) = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        val metaDir = File(storageDir, "meta").also { it.mkdirs() }
        val text = fetchString("$cdnBase/meta/maps.json")
        File(metaDir, "maps.json").writeText(text)
        File(storageDir, "maps.json").writeText(text)

        val mapDir = File(storageDir, "maps/${series.name}/${series.version}").also { it.mkdirs() }
        for (fname in listOf("countries.txt", "countries.txt.sig")) {
            val url = "$base/maps/${series.name}/${series.version}/$fname"
            val bytes = fetchBytes(url)
            File(mapDir, fname).writeBytes(bytes)
        }
    }

    suspend fun downloadMapFile(
        mapFile: MapFile,
        series: MapSeries,
        baseUrl: String,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        val dir = File(storageDir, "maps/${series.name}/${series.version}").also { it.mkdirs() }
        val output = File(dir, "${mapFile.id}.mwm")
        if (output.exists()) return@withContext output

        val url = "$base/maps/${series.name}/${series.version}/${mapFile.id}.mwm"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        try {
            val body = response.body ?: throw RuntimeException("Empty body")
            val total = body.contentLength()
            val part = File(dir, "${mapFile.id}.mwm.part")
            FileOutputStream(part).use { stream ->
                val buffer = ByteArray(8192)
                var downloaded = 0L
                body.byteStream().use { input ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        stream.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) onProgress(downloaded.toFloat() / total)
                    }
                }
            }
            part.renameTo(output)
        } finally {
            response.close()
        }
        output
    }

    fun getDownloadedMaps(series: MapSeries): List<String> {
        val dir = File(storageDir, "maps/${series.name}/${series.version}")
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.extension == "mwm" }
            ?.map { it.nameWithoutExtension }
            ?.sorted() ?: emptyList()
    }

    fun scanDownloadedMaps(): Map<String, List<String>> {
        val mapsDir = File(storageDir, "maps")
        if (!mapsDir.exists()) return emptyMap()
        val result = mutableMapOf<String, List<String>>()
        mapsDir.listFiles()?.forEach { seriesDir ->
            seriesDir.listFiles()?.forEach { versionDir ->
                val name = "${seriesDir.name} / ${versionDir.name}"
                val files = versionDir.listFiles()
                    ?.filter { it.extension == "mwm" }
                    ?.map { it.nameWithoutExtension }
                    ?.sorted()
                if (!files.isNullOrEmpty()) result[name] = files
            }
        }
        return result
    }

    fun getStorageDir(): File = storageDir

    fun comapsApkFile(): File = File(storageDir, COMAPS_APK_FILENAME)

    fun comapsVersionFile(): File = File(storageDir, COMAPS_VERSION_FILENAME)

    fun getCurrentApkVersion(): Int? {
        val f = comapsVersionFile()
        if (!f.exists()) return null
        return f.readText().trim().toIntOrNull()
    }

    fun isComapsApkDownloaded(): Boolean = comapsApkFile().exists()

    suspend fun fetchLatestApkInfo(): Pair<Int, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(FDROID_API_URL)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.144 Mobile Safari/537.36")
            .header("Accept", "application/json")
            .build()
        val response = client.newCall(request).execute()
        try {
            val text = response.body?.string() ?: throw RuntimeException("Empty response from $FDROID_API_URL")
            val json = gson.fromJson(text, JsonElement::class.java).asJsonObject
            val versionCode = json.get("suggestedVersionCode").asInt
            val url = "$FDROID_APK_BASE/app.comaps.fdroid_$versionCode.apk"
            Pair(versionCode, url)
        } finally {
            response.close()
        }
    }

    suspend fun downloadCoMapsApk(
        onProgress: (Float) -> Unit,
        apkUrl: String,
        versionCode: Int,
        forceRefresh: Boolean = false
    ): File = withContext(Dispatchers.IO) {
        val output = comapsApkFile()
        if (!forceRefresh && output.exists()) return@withContext output
        val request = Request.Builder()
            .url(apkUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.144 Mobile Safari/537.36")
            .header("Accept", "application/octet-stream,*/*")
            .build()
        val response = client.newCall(request).execute()
        try {
            val body = response.body ?: throw RuntimeException("Empty body")
            val total = body.contentLength()
            val part = File(storageDir, "$COMAPS_APK_FILENAME.part")
            FileOutputStream(part).use { stream ->
                val buffer = ByteArray(262144)
                var downloaded = 0L
                body.byteStream().use { input ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        stream.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) onProgress(downloaded.toFloat() / total)
                    }
                }
            }
            part.renameTo(output)
            comapsVersionFile().writeText(versionCode.toString())
        } finally {
            response.close()
        }
        output
    }

    private fun fetchString(url: String): String {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        try {
            return response.body?.string() ?: throw RuntimeException("Empty response from $url")
        } finally {
            response.close()
        }
    }

    private fun fetchBytes(url: String): ByteArray {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        try {
            return response.body?.bytes() ?: throw RuntimeException("Empty response from $url")
        } finally {
            response.close()
        }
    }
}
