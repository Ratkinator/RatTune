/*
 * NowPlayingServer.kt
 * Lightweight localhost HTTP server that exposes OuterTune's currently playing
 * track as a JSON API on http://localhost:9863/now-playing
 *
 * This allows external apps on the same device (e.g. the Revenge Discord plugin)
 * to poll for the current playback state without any special permissions.
 *
 * Endpoints:
 *   GET /now-playing  → JSON object with track info (see NowPlayingResponse)
 *   GET /health       → {"status":"ok"}
 *
 * The server binds ONLY to 127.0.0.1 (loopback) so it is never reachable from
 * outside the device.
 */

package com.dd3boh.outertune.playback

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress

class NowPlayingServer(private val musicService: MusicService) {

    companion object {
        const val TAG = "NowPlayingServer"
        const val PORT = 9863
    }

    private var serverScope: CoroutineScope? = null
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        if (serverJob?.isActive == true) return

        serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        serverJob = serverScope!!.launch {
            try {
                // Bind ONLY to loopback — never exposed to the network
                serverSocket = ServerSocket(PORT, 10, InetAddress.getByName("127.0.0.1"))
                Log.i(TAG, "NowPlayingServer listening on localhost:$PORT")

                while (isActive) {
                    val client: Socket = try {
                        serverSocket!!.accept()
                    } catch (e: Exception) {
                        if (isActive) Log.w(TAG, "accept() failed: ${e.message}")
                        break
                    }
                    // Handle each connection on the same IO thread pool
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}")
            } finally {
                serverSocket?.close()
                Log.i(TAG, "NowPlayingServer stopped")
            }
        }
    }

    fun stop() {
        serverJob?.cancel()
        serverSocket?.close()
        serverScope?.cancel()
        serverJob = null
        serverSocket = null
        serverScope = null
        Log.i(TAG, "NowPlayingServer shutdown requested")
    }

    // ── Request handling ──────────────────────────────────────────────────────

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        socket.soTimeout = 3000
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val writer = PrintWriter(s.getOutputStream(), true)

                // Read first line: "GET /path HTTP/1.1"
                val requestLine = reader.readLine() ?: return@use
                val path = requestLine.split(" ").getOrNull(1) ?: "/"

                // Consume remaining headers (required before writing response)
                while (reader.readLine()?.isNotBlank() == true) { /* drain */ }

                val (statusCode, body) = when {
                    path.startsWith("/now-playing") -> 200 to buildNowPlayingJson()
                    path.startsWith("/health")      -> 200 to """{"status":"ok"}"""
                    else                            -> 404 to """{"error":"not found"}"""
                }

                writeHttpResponse(writer, statusCode, body)
            }
        } catch (e: Exception) {
            Log.v(TAG, "Client error: ${e.message}")
        }
    }

    private fun writeHttpResponse(writer: PrintWriter, status: Int, body: String) {
        val statusText = if (status == 200) "OK" else if (status == 404) "Not Found" else "Error"
        writer.print("HTTP/1.1 $status $statusText\r\n")
        writer.print("Content-Type: application/json; charset=utf-8\r\n")
        writer.print("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
        writer.print("Access-Control-Allow-Origin: *\r\n")
        writer.print("Connection: close\r\n")
        writer.print("\r\n")
        writer.print(body)
        writer.flush()
    }

    // ── JSON builder ──────────────────────────────────────────────────────────

    /**
     * Build the now-playing JSON response from the current MusicService state.
     *
     * Example response when playing:
     * {
     *   "isPlaying": true,
     *   "title": "Blinding Lights",
     *   "artist": "The Weeknd",
     *   "artists": ["The Weeknd"],
     *   "album": "After Hours",
     *   "thumbnailUrl": "https://lh3.googleusercontent.com/...",
     *   "duration": 200,
     *   "position": 45000,
     *   "songId": "4NpFxQe2UvQ",
     *   "isLocal": false
     * }
     *
     * Example response when nothing is playing:
     * {
     *   "isPlaying": false
     * }
     */
    private fun buildNowPlayingJson(): String {
        return try {
            val player = musicService.player
            val metadata = musicService.currentMediaMetadata.value

            val json = JSONObject()

            if (metadata == null) {
                json.put("isPlaying", false)
                return json.toString()
            }

            // isPlaying: only true when actually playing (not paused/buffering)
            val isPlaying = player.isPlaying && player.playWhenReady

            json.put("isPlaying", isPlaying)
            json.put("title", metadata.title)
            json.put("artist", metadata.artists.joinToString(", ") { it.name })

            // Full artists array for richer clients
            val artistsArr = org.json.JSONArray()
            metadata.artists.forEach { artistsArr.put(it.name) }
            json.put("artists", artistsArr)

            json.put("album", metadata.album?.title ?: JSONObject.NULL)
            json.put("thumbnailUrl", metadata.thumbnailUrl ?: JSONObject.NULL)

            // duration in seconds (metadata stores it as seconds already)
            json.put("duration", metadata.duration)

            // current position in milliseconds
            json.put("position", player.currentPosition)

            json.put("songId", metadata.id)
            json.put("isLocal", metadata.isLocal)

            json.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error building JSON: ${e.message}")
            JSONObject().apply { put("isPlaying", false) }.toString()
        }
    }
}
