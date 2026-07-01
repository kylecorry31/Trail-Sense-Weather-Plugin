package com.kylecorry.trail_sense.plugin.weather.service

import com.kylecorry.andromeda.ipc.server.InterprocessCommunicationRouter
import com.kylecorry.andromeda.ipc.server.InterprocessCommunicationService
import com.kylecorry.andromeda.json.fromJson
import com.kylecorry.trail_sense.plugin.weather.models.MapTileLayerRequest
import com.kylecorry.trail_sense.plugin.weather.models.RegistrationFeaturesResponse
import com.kylecorry.trail_sense.plugin.weather.models.RegistrationMapLayerAttributionResponse
import com.kylecorry.trail_sense.plugin.weather.models.RegistrationMapLayerResponse
import com.kylecorry.trail_sense.plugin.weather.models.RegistrationResponse

class WeatherPluginService : InterprocessCommunicationService() {

    private val nwsTileClient by lazy { NationalWeatherServiceWMSClient(this) }

    override val router: InterprocessCommunicationRouter
        get() = InterprocessCommunicationRouter(
            buildMap {
                put("/registration") { _, _ ->
                    success(
                        RegistrationResponse(
                            RegistrationFeaturesResponse(
                                mapLayers = Layers.tiles.map { it.toRegistrationResponse() }
                            )
                        )
                    )
                }

                Layers.tiles.forEach { layer ->
                    put(layer.endpoint) { _, request ->
                        val parsedPayload = request.payload?.fromJson<MapTileLayerRequest>()
                        parsedPayload?.let { success(nwsTileClient.getTile(layer, it)) }
                            ?: badRequest()
                    }
                }
            }
        )

    private fun WebMapServiceLayer.toRegistrationResponse(): RegistrationMapLayerResponse {
        return RegistrationMapLayerResponse(
            endpoint = endpoint,
            name = name,
            layerType = "tile",
            attribution = RegistrationMapLayerAttributionResponse(
                attribution = "NOAA nowCOAST"
            ),
            description = description,
            minZoomLevel = 0,
            isTimeDependent = isTimeDependent,
            refreshInterval = refreshInterval
        )
    }
}

private object Layers {
    val tiles = listOf(
        WebMapServiceLayer(
            endpoint = "/tiles/nws-radar",
            name = "NWS Weather Radar",
            wmsLayer = "base_reflectivity_mosaic",
            description = "Latest US base reflectivity radar mosaic.",
            cacheDir = "nws-radar",
            refreshInterval = 240_000L,
            baseUrl = "https://nowcoast.noaa.gov/geoserver/observations/weather_radar/ows"
        ),
        WebMapServiceLayer(
            endpoint = "/tiles/lightning-density",
            name = "NWS Lightning Strike Density",
            wmsLayer = "lightning_detection:ldn_lightning_strike_density",
            description = "Latest US lightning strike density.",
            cacheDir = "lightning-density",
            refreshInterval = 900_000L
        ),
        WebMapServiceLayer(
            endpoint = "/tiles/precipitation-amount",
            name = "NWS Precipitation Amounts",
            wmsLayer = "ndfd_precipitation:6hr_precipitation_amount",
            description = "Latest US 6-hour precipitation amount forecast.",
            cacheDir = "precipitation-amount",
            refreshInterval = 3_600_000L
        ),
        WebMapServiceLayer(
            endpoint = "/tiles/tropical-cyclones",
            name = "NWS Tropical Cyclones",
            wmsLayer = "tropical_cyclones:active_tropical_cyclones",
            description = "Latest US tropical cyclone forecast.",
            cacheDir = "tropical-cyclones",
            refreshInterval = 900_000L
        )
    )
}
