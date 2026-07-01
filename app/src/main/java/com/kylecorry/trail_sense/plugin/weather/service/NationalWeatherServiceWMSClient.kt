package com.kylecorry.trail_sense.plugin.weather.service

import android.content.Context
import com.kylecorry.andromeda.files.CacheFileSystem
import com.kylecorry.andromeda.net.HttpClient
import com.kylecorry.trail_sense.plugin.weather.models.MapTileLayerRequest
import com.kylecorry.trail_sense.plugin.weather.models.getTile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

class NationalWeatherServiceWMSClient(context: Context) {

    private val client = HttpClient()
    private val cacheFileSystem = CacheFileSystem(context.applicationContext)
    private val caches = mutableMapOf<WebMapServiceLayer, DiskLRUCache<String, ByteArray>>()
    private val cacheMutex = Mutex()

    suspend fun getTile(layer: WebMapServiceLayer, request: MapTileLayerRequest): ByteArray? {
        val cache = getCache(layer)
        val key = getCacheKey(layer, request)
        val url = buildUrl(layer, request)
        val bytes = cache.getOrPut(key) {
            fetch(url) ?: ByteArray(0)
        }

        return if (bytes.isEmpty()) {
            cache.invalidate(key)
            null
        } else {
            bytes
        }
    }

    private suspend fun getCache(layer: WebMapServiceLayer): DiskLRUCache<String, ByteArray> {
        return cacheMutex.withLock {
            caches.getOrPut(layer) {
                DiskLRUCache(
                    baseFolderPath = cacheFileSystem
                        .getDirectory(layer.cacheDir)
                        .path,
                    size = MAX_CACHE_SIZE,
                    duration = Duration.ofMillis(layer.refreshInterval),
                    getFilename = { "$it.png" },
                    serialize = { it },
                    deserialize = { it }
                )
            }
        }
    }

    private fun buildUrl(layer: WebMapServiceLayer, request: MapTileLayerRequest): String {
        val bounds = request.getTile().getBounds()
        val params = mutableMapOf(
            "service" to "WMS",
            "version" to "1.1.1",
            "request" to "GetMap",
            "layers" to layer.wmsLayer,
            "styles" to "",
            "format" to "image/png",
            "transparent" to "true",
            "srs" to "EPSG:4326",
            "width" to TILE_SIZE.toString(),
            "height" to TILE_SIZE.toString(),
            "bbox" to "${bounds.west},${bounds.south},${bounds.east},${bounds.north}"
        )

        if (layer.isTimeDependent) {
            params["time"] =
                DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(request.time))
        }

        return layer.baseUrl + "?" + params.entries.joinToString("&") { (key, value) ->
            "${key.encode()}=${value.encode()}"
        }
    }

    private suspend fun fetch(url: String): ByteArray? {
        val response = client.send(
            url,
            headers = mapOf("User-Agent" to "Trail Sense Weather Plugin"),
            readTimeout = Duration.ofSeconds(10),
            connectTimeout = Duration.ofSeconds(10)
        )

        if (!response.isSuccessful()) {
            return null
        }

        val contentType = response.headers.firstValue("Content-Type").orEmpty()
        if (!contentType.startsWith("image/")) {
            return null
        }

        return response.content
    }

    private fun Map<String, List<String>>.firstValue(key: String): String? {
        return entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.firstOrNull()
    }

    private fun String.encode(): String {
        return URLEncoder.encode(this, StandardCharsets.UTF_8.name())
    }

    private fun getCacheKey(layer: WebMapServiceLayer, request: MapTileLayerRequest): String {
        return if (layer.isTimeDependent) {
            "${request.z}/${request.x}/${request.y}/${request.time}"
        } else {
            "${request.z}/${request.x}/${request.y}"
        }
    }

    private companion object {
        const val TILE_SIZE = 256
        const val MAX_CACHE_SIZE = 512
    }
}
