package com.offlines.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.offlines.download.MapDownloader
import com.offlines.model.DownloadState
import com.offlines.model.MapFile
import com.offlines.model.MapSeries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(downloader: MapDownloader, visible: Boolean) {
    val scope = rememberCoroutineScope()
    var seriesList by remember { mutableStateOf<List<MapSeries>>(emptyList()) }
    var selectedSeries by remember { mutableStateOf<MapSeries?>(null) }
    var availableMaps by remember { mutableStateOf<List<MapFile>>(emptyList()) }
    var selectedMapIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var downloadStates by remember { mutableStateOf<Map<String, DownloadState>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger == 0) return@LaunchedEffect
        loading = true
        error = null
        withContext(NonCancellable) {
            try {
                seriesList = downloader.fetchMapSeries()
                val s = seriesList.firstOrNull()
                selectedSeries = s
                if (s != null) {
                    val maps = downloader.fetchCountries(s)
                    availableMaps = maps
                    selectedMapIds = maps.filter { it.id == "World" || it.id == "Coasts" }.map { it.id }.toSet()
                    searchQuery = ""
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = "Failed to refresh: ${e.message}"
            }
        }
        loading = false
    }

    data class ListItem(val key: String, val country: String, val mapFile: MapFile?)

    val flatItems = remember(availableMaps, searchQuery) {
        val groups = availableMaps.groupBy { it.country }.toSortedMap()
        val items = mutableListOf<ListItem>()
        val seen = mutableSetOf<String>()
        val filtered = if (searchQuery.isBlank()) groups
        else {
            val q = searchQuery.lowercase()
            groups.mapValues { (_, maps) ->
                maps.filter { it.id.lowercase().contains(q) }
            }.filter { (_, maps) -> maps.isNotEmpty() }
        }
        for ((country, maps) in filtered) {
            val hk = "h_$country"
            if (seen.add(hk)) items.add(ListItem(hk, country, null))
            for (mf in maps) {
                val mk = "m_${mf.id}"
                if (seen.add(mk)) items.add(ListItem(mk, country, mf))
            }
        }
        items
    }

    Box(Modifier.fillMaxSize()) {
        if (visible) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Download Maps", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { refreshTrigger++ }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh map list")
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    if (seriesList.isEmpty() && loading) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("Loading...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        error?.let {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                Text(it, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                            Spacer(Modifier.height(8.dp))
                        }

                        seriesList.takeIf { it.isNotEmpty() }?.let {
                            Text("Map Series", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                it.forEach { series ->
                                    FilterChip(
                                        selected = series == selectedSeries,
                                        onClick = {
                                        scope.launch {
                                            selectedSeries = series
                                            availableMaps = emptyList()
                                            selectedMapIds = emptySet()
                                            searchQuery = ""
                                            loading = true
                                            error = null
                                            withContext(NonCancellable) {
                                                try {
                                                    val maps = downloader.fetchCountries(series)
                                                    availableMaps = maps
                                                    selectedMapIds = maps.filter { it.id == "World" || it.id == "Coasts" }.map { it.id }.toSet()
                                                } catch (e: CancellationException) {
                                                        throw e
                                                    } catch (e: Throwable) {
                                                        error = "Failed to fetch map list: ${e.message}"
                                                    }
                                                }
                                                loading = false
                                            }
                                        },
                                        label = { Text("${series.name} (${series.version})") }
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        if (availableMaps.isNotEmpty()) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("Search areas...") },
                                leadingIcon = { Icon(Icons.Default.Search, null) },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Default.Close, "Clear")
                                        }
                                    }
                                },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors()
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${flatItems.count { it.mapFile != null }} / ${availableMaps.size} maps",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))

                            LazyColumn(
                                modifier = Modifier.weight(1f).fillMaxWidth()
                            ) {
                                items(count = flatItems.size, key = { flatItems[it].key }) { index ->
                                    val item = flatItems[index]
                                    if (item.mapFile == null) {
                                        Text(
                                            item.country,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp)
                                        )
                                    } else {
                                        val mf = item.mapFile
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (mf.id in selectedMapIds)
                                                    MaterialTheme.colorScheme.primaryContainer
                                                else MaterialTheme.colorScheme.surface
                                            )
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 0.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Checkbox(
                                                    checked = mf.id in selectedMapIds,
                                                    onCheckedChange = { checked ->
                                                        selectedMapIds = if (checked)
                                                            selectedMapIds + mf.id
                                                        else selectedMapIds - mf.id
                                                    }
                                                )
                                                Column(Modifier.weight(1f)) {
                                                    Text(mf.id, style = MaterialTheme.typography.bodySmall)
                                                }
                                                val s = downloadStates[mf.id]
                                                if (s is DownloadState.Done) {
                                                    Spacer(Modifier.width(4.dp))
                                                    Text("✓", color = MaterialTheme.colorScheme.primary)
                                                }
                                                if (s is DownloadState.Error) {
                                                    Spacer(Modifier.width(4.dp))
                                                    Text("✗", color = MaterialTheme.colorScheme.error)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (!loading) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text("Select a map series above",
                                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                val clickable = selectedMapIds.isNotEmpty() && !loading && !isDownloading
                val label = if (isDownloading) "Downloading ${(downloadProgress * 100).toInt()}%"
                    else "Download Selected (${selectedMapIds.size})"

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clipToBounds()
                        .let { mod ->
                            if (isDownloading) mod.background(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            ) else mod
                        }
                ) {
                    if (isDownloading) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(downloadProgress.coerceIn(0f, 1f))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                        )
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                try {
                                    val series = selectedSeries ?: return@launch
                                    val mapsToDownload = availableMaps.filter { it.id in selectedMapIds }
                                    if (mapsToDownload.isEmpty()) return@launch
                                    isDownloading = true
                                    downloadProgress = 0f
                                    error = null

                                    val server = withContext(Dispatchers.IO) {
                                        downloader.getBestServer(series.version)
                                    }

                                    withContext(Dispatchers.IO) {
                                        downloader.downloadMeta(series, server)
                                    }

                                    val total = mapsToDownload.size.toFloat()
                                    var completed = 0
                                    for (map in mapsToDownload) {
                                        val dir = File(downloader.getStorageDir(), "maps/${series.name}/${series.version}")
                                        val alreadyExists = File(dir, "${map.id}.mwm").exists()
                                        if (!alreadyExists) {
                                            downloadStates = downloadStates + (map.id to DownloadState.Downloading(map.id, 0f))
                                        }
                                        try {
                                            withContext(Dispatchers.IO) {
                                                downloader.downloadMapFile(map, series, server) { progress ->
                                                    downloadProgress = (completed + progress) / total
                                                }
                                            }
                                    } catch (e: Throwable) {
                                        downloadStates = downloadStates + (map.id to DownloadState.Error(map.id, e.message ?: "Unknown"))
                                        completed++
                                        downloadProgress = completed / total
                                        continue
                                    }
                                    downloadStates = downloadStates + (map.id to DownloadState.Done(map.id))
                                        completed++
                                        downloadProgress = completed / total
                                    }
                                } catch (e: Throwable) {
                                    error = "Download failed: ${e.message}"
                                } finally {
                                    isDownloading = false
                                    downloadProgress = 0f
                                }
                            }
                        },
                        enabled = clickable,
                        modifier = Modifier.fillMaxSize(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDownloading)
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                            else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        }
    }
}
