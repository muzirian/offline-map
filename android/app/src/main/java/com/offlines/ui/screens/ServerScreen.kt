package com.offlines.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.ui.draw.clipToBounds
import com.offlines.download.MapDownloader
import com.offlines.server.MapServer
import com.offlines.server.MapServerService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(downloader: MapDownloader, visible: Boolean) {
    val context = LocalContext.current
    var downloadedMaps by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var isRunning by remember { mutableStateOf(false) }
    var serverPort by remember { mutableStateOf(8080) }
    var ipAddresses by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val comapsApkFileName = MapDownloader.COMAPS_APK_FILENAME
    var comapsApkDownloaded by remember { mutableStateOf(downloader.isComapsApkDownloaded()) }
    var comapsApkDownloading by remember { mutableStateOf(false) }
    var comapsApkProgress by remember { mutableStateOf(0f) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            isRunning = true
            try {
                val intent = Intent(context, MapServerService::class.java).apply {
                    putExtra("port", serverPort)
                    putExtra("storagePath", downloader.getStorageDir().absolutePath)
                }
                context.startForegroundService(intent)
            } catch (e: Exception) {
                isRunning = false
            }
        }
    }

    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        downloadedMaps = downloader.scanDownloadedMaps()
        ipAddresses = try { MapServer.getLocalIpAddresses() } catch (_: Exception) { emptyList() }
    }

    Box(Modifier.fillMaxSize()) {
        if (visible) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Map Server", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Switch(
                        checked = isRunning,
                        onCheckedChange = { running ->
                            if (running) {
                                if (Build.VERSION.SDK_INT >= 33 &&
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                                    != PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    return@Switch
                                }
                                isRunning = true
                                try {
                                    val intent = Intent(context, MapServerService::class.java).apply {
                                        putExtra("port", serverPort)
                                        putExtra("storagePath", downloader.getStorageDir().absolutePath)
                                    }
                                    context.startForegroundService(intent)
                                } catch (e: Exception) {
                                    isRunning = false
                                }
                            } else {
                                isRunning = false
                                context.stopService(Intent(context, MapServerService::class.java))
                            }
                        }
                    )
                }

                Spacer(Modifier.height(8.dp))

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isRunning) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (isRunning) "Map server running" else "Map server stopped",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (isRunning) {
                            Spacer(Modifier.height(8.dp))
                            ipAddresses.forEach { ip ->
                                val url = "http://$ip:$serverPort"
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            url,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    QrCodeImage(url, size = 80.dp)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Scan this QR to copy or enter this URL after clicking Settings button on top and Save",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Remove it from advanced settings when you have internet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Card {
                    Column(Modifier.padding(12.dp)) {
                        Text("CoMaps APK", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        if (comapsApkDownloading) {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(24.dp).clipToBounds()
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxHeight()
                                        .fillMaxWidth(comapsApkProgress.coerceIn(0f, 1f))
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                )
                                Text(
                                    "Downloading ${(comapsApkProgress * 100).toInt()}%",
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        } else if (comapsApkDownloaded && isRunning) {
                            ipAddresses.forEach { ip ->
                                val apkUrl = "http://$ip:$serverPort/$comapsApkFileName"
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            apkUrl,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    QrCodeImage(apkUrl, size = 80.dp)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Open this URL on your phone to download CoMaps",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else if (comapsApkDownloaded) {
                            Text(
                                "APK ready. Start the server to get the download URL.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                "Download CoMaps APK so other phones can install it from this server (no internet needed on their side).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    scope.launch {
                                        comapsApkDownloading = true
                                        comapsApkProgress = 0f
                                        try {
                                            downloader.downloadCoMapsApk { p ->
                                                comapsApkProgress = p
                                            }
                                            comapsApkDownloaded = true
                                        } catch (_: Exception) {
                                        }
                                        comapsApkDownloading = false
                                    }
                                }
                            ) {
                                Text("Download CoMaps APK (63 MB)")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("Downloaded Maps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))

                if (downloadedMaps.isEmpty()) {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            "No maps downloaded yet. Go to the Download tab first.",
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        downloadedMaps.forEach { (seriesName, maps) ->
                            item(key = "srv_header_$seriesName") {
                                Text(
                                    seriesName,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                            items(maps, key = { "srv_map_$it" }) { mapName ->
                                Card(Modifier.fillMaxWidth()) {
                                    Text(
                                        mapName,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QrCodeImage(data: String, size: Dp) {
    val bitMatrix = remember(data) {
        try {
            QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, 256, 256)
        } catch (_: Exception) { null }
    }
    Canvas(modifier = Modifier.size(size)) {
        bitMatrix?.let { matrix ->
            val cellW = size.toPx() / matrix.width
            val cellH = size.toPx() / matrix.height
            for (x in 0 until matrix.width) {
                for (y in 0 until matrix.height) {
                    if (matrix[x, y]) {
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(x * cellW, y * cellH),
                            size = Size(cellW, cellH)
                        )
                    }
                }
            }
        }
    }
}
