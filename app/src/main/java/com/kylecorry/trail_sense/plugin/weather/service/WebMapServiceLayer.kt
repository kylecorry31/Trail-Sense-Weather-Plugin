package com.kylecorry.trail_sense.plugin.weather.service

data class WebMapServiceLayer(
    val endpoint: String,
    val name: String,
    val wmsLayer: String,
    val description: String,
    val cacheDir: String,
    val refreshInterval: Long,
    val baseUrl: String = "https://nowcoast.noaa.gov/geoserver/wms",
    val isTimeDependent: Boolean = false
)
