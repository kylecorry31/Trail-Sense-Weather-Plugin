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

    private val nwsRadarTileClient by lazy { NwsRadarTileClient(this) }

    override val router: InterprocessCommunicationRouter
        get() = InterprocessCommunicationRouter(
            mapOf(
                "/registration" to { _, _ ->
                    success(
                        RegistrationResponse(
                            RegistrationFeaturesResponse(
                                mapLayers = listOf(
                                    RegistrationMapLayerResponse(
                                        endpoint = "/tiles/nws-radar",
                                        name = "NWS Weather Radar",
                                        layerType = "tile",
                                        attribution = RegistrationMapLayerAttributionResponse(
                                            attribution = "NOAA nowCOAST",
                                            longAttribution = "Weather radar base reflectivity mosaics from NOAA nowCOAST."
                                        ),
                                        description = "Latest NOAA/NWS base reflectivity radar mosaic.",
                                        minZoomLevel = 0,
                                        refreshInterval = 240000
                                    )
                                )
                            )
                        )
                    )
                },
                "/tiles/nws-radar" to { _, request ->
                    val parsedPayload = request.payload?.fromJson<MapTileLayerRequest>()
                    parsedPayload?.let { success(nwsRadarTileClient.getTile(it)) } ?: badRequest()
                }
            )
        )
}
