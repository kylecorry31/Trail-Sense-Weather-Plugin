package com.kylecorry.trail_sense.plugin.weather.service

import android.content.Context
import com.kylecorry.andromeda.files.CacheFileSystem
import com.kylecorry.andromeda.net.HttpClient
import com.kylecorry.trail_sense.plugin.weather.models.MapTileLayerRequest
import com.kylecorry.trail_sense.plugin.weather.models.getTile
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration

class NwsRadarTileClient(context: Context) {

    private val client = HttpClient()
    private val cache = DiskLRUCache<String, ByteArray>(
        baseFolderPath = CacheFileSystem(context.applicationContext)
            .getDirectory(CACHE_DIR)
            .path,
        size = MAX_CACHE_SIZE,
        duration = Duration.ofMillis(CACHE_DURATION_MILLIS),
        getFilename = { "$it.png" },
        serialize = { it },
        deserialize = { it }
    )

    suspend fun getTile(request: MapTileLayerRequest): ByteArray? {
        val key = getCacheKey(request)
        val url = buildUrl(request)
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

    private fun buildUrl(request: MapTileLayerRequest): String {
        val bounds = request.getTile().getBounds()
        val params = mapOf(
            "service" to "WMS",
            "version" to "1.1.1",
            "request" to "GetMap",
            "layers" to LAYER,
            "styles" to "",
            "format" to "image/png",
            "transparent" to "true",
            "srs" to "EPSG:4326",
            "width" to TILE_SIZE.toString(),
            "height" to TILE_SIZE.toString(),
            "bbox" to "${bounds.west},${bounds.south},${bounds.east},${bounds.north}"
        )

        return BASE_URL + "?" + params.entries.joinToString("&") { (key, value) ->
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

    private fun getCacheKey(request: MapTileLayerRequest): String {
        return "${request.z}/${request.x}/${request.y}"
    }

    private companion object {
        const val TILE_SIZE = 256
        const val CACHE_DURATION_MILLIS = 240_000L
        const val MAX_CACHE_SIZE = 512
        const val CACHE_DIR = "nws-radar"
        const val BASE_URL =
            "https://nowcoast.noaa.gov/geoserver/observations/weather_radar/ows"
        const val LAYER = "base_reflectivity_mosaic"
    }
}
