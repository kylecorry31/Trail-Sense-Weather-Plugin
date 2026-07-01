package com.kylecorry.trail_sense.plugin.weather.service

import com.kylecorry.trail_sense.plugin.weather.models.MapTileLayerRequest
import com.kylecorry.trail_sense.plugin.weather.models.getTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan

object NwsRadarTileClient {
    private const val TILE_SIZE = 256
    private const val CACHE_DURATION_MILLIS = 240_000L
    private const val MAX_CACHE_SIZE = 512
    private const val BASE_URL =
        "https://nowcoast.noaa.gov/geoserver/observations/weather_radar/ows"
    private const val LAYER = "base_reflectivity_mosaic"

    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<String, CachedTile>(MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedTile>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    suspend fun getTile(request: MapTileLayerRequest): ByteArray? {
        val key = "${request.z}/${request.x}/${request.y}"
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            cache[key]?.takeIf { now < it.expiresAt }?.let { return it.bytes }
        }

        val url = buildUrl(request)
        val bytes = fetch(url) ?: return null
        synchronized(cacheLock) {
            cache[key] = CachedTile(bytes, now + CACHE_DURATION_MILLIS)
        }
        return bytes
    }

    private fun buildUrl(request: MapTileLayerRequest): String {
        val bounds = request.getTile().getBounds().toWebMercator()
        val params = mapOf(
            "service" to "WMS",
            "version" to "1.3.0",
            "request" to "GetMap",
            "layers" to LAYER,
            "styles" to "",
            "format" to "image/png",
            "transparent" to "true",
            "crs" to "EPSG:3857",
            "width" to TILE_SIZE.toString(),
            "height" to TILE_SIZE.toString(),
            "bbox" to "${bounds.minX},${bounds.minY},${bounds.maxX},${bounds.maxY}"
        )

        return BASE_URL + "?" + params.entries.joinToString("&") { (key, value) ->
            "${key.encode()}=${value.encode()}"
        }
    }

    private suspend fun fetch(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Trail Sense Weather Plugin")
        }

        try {
            if (connection.responseCode !in 200..299) {
                return@withContext null
            }

            val contentType = connection.contentType.orEmpty()
            if (!contentType.startsWith("image/")) {
                return@withContext null
            }

            connection.inputStream.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun String.encode(): String {
        return URLEncoder.encode(this, StandardCharsets.UTF_8.name())
    }

    private fun com.kylecorry.trail_sense.plugin.weather.models.TileBounds.toWebMercator(): WebMercatorBounds {
        return WebMercatorBounds(
            minX = longitudeToWebMercator(west),
            minY = latitudeToWebMercator(south),
            maxX = longitudeToWebMercator(east),
            maxY = latitudeToWebMercator(north)
        )
    }

    private fun longitudeToWebMercator(longitude: Double): Double {
        return longitude * 20037508.34 / 180.0
    }

    private fun latitudeToWebMercator(latitude: Double): Double {
        val clamped = latitude.coerceIn(-85.05112878, 85.05112878)
        return ln(tan((90.0 + clamped) * PI / 360.0)) / (PI / 180.0) * 20037508.34 / 180.0
    }

    private data class CachedTile(
        val bytes: ByteArray,
        val expiresAt: Long
    )

    private data class WebMercatorBounds(
        val minX: Double,
        val minY: Double,
        val maxX: Double,
        val maxY: Double
    )
}
