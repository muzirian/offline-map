package com.offlines.server

import java.io.File
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

class MapServer(
    private val rootDir: File,
    private val port: Int = 8080
) {
    private var serverSocket: ServerSocket? = null
    private var running = false
    private val threads = mutableListOf<Thread>()

    fun start(onClientConnected: (() -> Unit)? = null) {
        if (running) return
        running = true
        try {
            serverSocket = ServerSocket().also {
                it.reuseAddress = true
                it.bind(InetSocketAddress(port))
            }
        } catch (e: java.net.BindException) {
            running = false
            return
        }
        thread(isDaemon = true, name = "map-server-accept") {
            while (running) {
                try {
                    val client = serverSocket!!.accept()
                    onClientConnected?.invoke()
                    threads.add(thread(isDaemon = true, name = "map-server-client") {
                        handleClient(client)
                    })
                } catch (_: Exception) {
                    if (!running) break
                }
            }
        }
    }

    fun stop() {
        running = false
        serverSocket?.close()
        serverSocket = null
    }

    val isRunning: Boolean get() = running && serverSocket != null
    val portNumber: Int get() = serverSocket?.localPort ?: port

    private fun handleClient(client: Socket) {
        try {
            val reader = client.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val rawPath = parts[1]

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
            }

            val path = URLDecoder.decode(rawPath, "UTF-8").removePrefix("/")
            val baseDir = rootDir.canonicalFile
            var file = File(baseDir, path).canonicalFile
            if (!file.exists() && path == "maps.json") {
                val meta = File(baseDir, "meta/maps.json").canonicalFile
                if (meta.exists() && meta.startsWith(baseDir)) file = meta
            }

            if (!file.exists() || !file.startsWith(baseDir) || file.isDirectory) {
                sendResponse(client.getOutputStream(), 404, "Not Found", "text/plain", "Not Found")
                return
            }

            when (method) {
                "GET" -> handleGet(client.getOutputStream(), file, headers)
                "HEAD" -> handleHead(client.getOutputStream(), file)
                else -> sendResponse(client.getOutputStream(), 405, "Method Not Allowed", "text/plain", "")
            }
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun handleGet(out: OutputStream, file: File, headers: Map<String, String>) {
        val rangeHeader = headers["range"]
        val fileLen = file.length()
        val mime = mimeType(file)

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val range = rangeHeader.removePrefix("bytes=").trim()
            val dash = range.indexOf('-')
            val start = range.substring(0, if (dash < 0) range.length else dash).toLongOrNull() ?: 0
            val end = if (dash >= 0 && dash + 1 < range.length) {
                range.substring(dash + 1).toLongOrNull() ?: (fileLen - 1)
            } else fileLen - 1

            val len = end - start + 1
            val partial = "bytes $start-$end/$fileLen"
            out.write("HTTP/1.1 206 Partial Content\r\n".toByteArray())
            out.write("Content-Type: $mime\r\n".toByteArray())
            out.write("Content-Range: $partial\r\n".toByteArray())
            out.write("Content-Length: $len\r\n".toByteArray())
            out.write("Accept-Ranges: bytes\r\n".toByteArray())
            out.write("Connection: close\r\n".toByteArray())
            out.write("\r\n".toByteArray())
            file.inputStream().use { input ->
                input.skip(start)
                var remaining = len
                val buf = ByteArray(8192)
                while (remaining > 0) {
                    val read = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    out.write(buf, 0, read)
                    remaining -= read
                }
            }
        } else {
            out.write("HTTP/1.1 200 OK\r\n".toByteArray())
            out.write("Content-Type: $mime\r\n".toByteArray())
            out.write("Content-Length: $fileLen\r\n".toByteArray())
            out.write("Accept-Ranges: bytes\r\n".toByteArray())
            out.write("Connection: close\r\n".toByteArray())
            out.write("\r\n".toByteArray())
            file.inputStream().use { it.copyTo(out) }
        }
        out.flush()
    }

    private fun handleHead(out: OutputStream, file: File) {
        val mime = mimeType(file)
        out.write("HTTP/1.1 200 OK\r\n".toByteArray())
        out.write("Content-Type: $mime\r\n".toByteArray())
        out.write("Content-Length: ${file.length()}\r\n".toByteArray())
        out.write("Accept-Ranges: bytes\r\n".toByteArray())
        out.write("Connection: close\r\n".toByteArray())
        out.write("\r\n".toByteArray())
        out.flush()
    }

    private fun sendResponse(out: OutputStream, code: Int, message: String, mime: String, body: String) {
        val bytes = body.toByteArray()
        out.write("HTTP/1.1 $code $message\r\n".toByteArray())
        out.write("Content-Type: $mime\r\n".toByteArray())
        out.write("Content-Length: ${bytes.size}\r\n".toByteArray())
        out.write("Connection: close\r\n".toByteArray())
        out.write("\r\n".toByteArray())
        if (body.isNotEmpty()) out.write(bytes)
        out.flush()
    }

    private fun mimeType(file: File): String = when {
        file.extension == "mwm" -> "application/octet-stream"
        file.extension == "txt" -> "text/plain"
        file.extension == "json" -> "application/json"
        file.extension == "sig" -> "application/octet-stream"
        else -> "application/octet-stream"
    }

    companion object {
        fun getLocalIpAddresses(): List<String> {
            val ips = mutableListOf<String>()
            try {
                NetworkInterface.getNetworkInterfaces()?.asIterator()?.forEach { iface ->
                    if (iface.isLoopback || iface.name.startsWith("docker")) return@forEach
                    iface.inetAddresses?.asIterator()?.forEach { addr ->
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            ips.add(addr.hostAddress ?: "")
                        }
                    }
                }
            } catch (_: Exception) {}
            return ips
        }
    }
}
