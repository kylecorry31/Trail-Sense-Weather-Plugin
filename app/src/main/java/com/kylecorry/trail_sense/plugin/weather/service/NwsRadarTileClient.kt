package com.kylecorry.trail_sense.plugin.weather.service

import com.kylecorry.andromeda.net.HttpClient
import com.kylecorry.sol.science.geography.projections.MercatorProjection
import com.kylecorry.trail_sense.plugin.weather.models.MapTileLayerRequest
import com.kylecorry.trail_sense.plugin.weather.models.getTile
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration

object NwsRadarTileClient {
    private const val TILE_SIZE = 256
    private const val CACHE_DURATION_MILLIS = 240_000L
    private const val MAX_CACHE_SIZE = 512
    private const val WEB_MERCATOR_RADIUS_METERS = 6_378_137f
    private const val BASE_URL =
        "https://nowcoast.noaa.gov/geoserver/observations/weather_radar/ows"
    private const val LAYER = "base_reflectivity_mosaic"

    private val client = HttpClient()
    private val projection = MercatorProjection(WEB_MERCATOR_RADIUS_METERS)
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

    private suspend fun fetch(url: String): ByteArray? {
        val response = try {
            client.send(
                url,
                headers = mapOf("User-Agent" to "Trail Sense Weather Plugin"),
                readTimeout = Duration.ofSeconds(10),
                connectTimeout = Duration.ofSeconds(10)
            )
        } catch (_: Exception) {
            return null
        }

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

    private fun com.kylecorry.trail_sense.plugin.weather.models.TileBounds.toWebMercator(): WebMercatorBounds {
        val southwest = projection.toPixels(south, west)
        val northeast = projection.toPixels(north, east)
        return WebMercatorBounds(
            minX = southwest.x.toDouble(),
            minY = southwest.y.toDouble(),
            maxX = northeast.x.toDouble(),
            maxY = northeast.y.toDouble()
        )
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
