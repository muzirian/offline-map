package com.offlines.model

import com.google.gson.annotations.SerializedName

data class MapsMeta(
    @SerializedName("map-series") val mapSeries: Map<String, MapSeriesInfo>
)

data class MapSeriesInfo(
    val status: String? = null,
    val latest: Int = 0
)

data class MapFile(
    val id: String,
    val country: String
)

data class MapSeries(
    val name: String,
    val version: Int
)

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val mapName: String, val progress: Float) : DownloadState()
    data class Done(val mapName: String) : DownloadState()
    data class Error(val mapName: String, val message: String) : DownloadState()
}

data class ServerState(
    val running: Boolean = false,
    val port: Int = 0,
    val ipAddresses: List<String> = emptyList(),
    val clientCount: Int = 0
)
