package com.cantooscribe.httpserver

import android.Manifest
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Base64
import android.util.Log
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@CapacitorPlugin(
    name = "HttpServer",
    permissions = [
        Permission(
            alias = "notifications",
            strings = [Manifest.permission.POST_NOTIFICATIONS]
        )
    ]
)
class HttpServerPlugin : Plugin() {

    private val stateLock = Any()

    @Volatile private var server: LocalHttpServer? = null
    @Volatile private var currentPort: Int = 0
    @Volatile private var isStarting: Boolean = false
    @Volatile private var isStopping: Boolean = false
    @Volatile private var maxBodyBytes: Long = DEFAULT_MAX_BODY
    @Volatile private var fileBodyThresholdBytes: Long = DEFAULT_FILE_THRESHOLD

    /** Renamed from `bridge` to avoid shadowing `Plugin.bridge` (the Capacitor Bridge). */
    private val requestBridge = HttpRequestBridge()

    /** Pending start() call parked while we request POST_NOTIFICATIONS. */
    private var parkedStartCall: PluginCall? = null

    private fun appContext(): Context = this.context

    private fun tempDir(): File {
        val dir = File(appContext().cacheDir, "httpserver")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    @PluginMethod
    fun start(call: PluginCall) {
        synchronized(stateLock) {
            if (server != null) {
                call.resolve(currentResult())
                return
            }
            if (isStarting) {
                call.reject("A concurrent start() call is already in progress")
                return
            }
            isStarting = true
        }

        // Request POST_NOTIFICATIONS on API 33+ because the foreground service
        // notification is required. We still start the server if denied, but
        // we log a warning so the consumer is aware.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val state = getPermissionState("notifications")
            if (state != PermissionState.GRANTED) {
                parkedStartCall = call
                requestPermissionForAlias("notifications", call, "notificationsPermissionCallback")
                return
            }
        }

        doStart(call)
    }

    @PermissionCallback
    private fun notificationsPermissionCallback(call: PluginCall) {
        val state = getPermissionState("notifications")
        if (state != PermissionState.GRANTED) {
            Log.w(
                TAG,
                "POST_NOTIFICATIONS not granted; the foreground service notification " +
                    "will not be visible and the system may kill the process sooner."
            )
        }
        val pending = parkedStartCall ?: call
        parkedStartCall = null
        doStart(pending)
    }

    private fun doStart(call: PluginCall) {
        try {
            val requestedPort = call.getInt("port") ?: 0
            // PluginCall.getLong requires the underlying JSON value to be a
            // java.lang.Long, but Android's JSONObject stores JS numbers that
            // fit in int range as java.lang.Integer. Routing through getDouble
            // handles Integer/Long/Double uniformly and avoids silently
            // ignoring user-supplied values like `{ maxBodyBytes: 10_000_000 }`.
            maxBodyBytes = call.getDouble("maxBodyBytes")?.toLong()?.takeIf { it > 0 }
                ?: DEFAULT_MAX_BODY
            fileBodyThresholdBytes = call.getDouble("fileBodyThresholdBytes")?.toLong()?.takeIf { it > 0 }
                ?: DEFAULT_FILE_THRESHOLD

            val androidOpts = call.getObject("android")
            val notifTitle = androidOpts?.getString("notificationTitle") ?: "HTTP server running"
            val notifText = androidOpts?.getString("notificationText") ?: ""
            val channelId = androidOpts?.getString("channelId")
            val channelName = androidOpts?.getString("channelName")
            val smallIcon = androidOpts?.getString("smallIconResourceName")

            synchronized(stateLock) {
                if (server != null) {
                    call.resolve(currentResult())
                    return
                }
                try {
                    val port = if (requestedPort > 0) requestedPort else pickFreePort()
                    val srv = LocalHttpServer(port)
                    srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                    server = srv
                    currentPort = port
                    isStopping = false
                } catch (e: Throwable) {
                    // PluginCall.reject(String, Exception) cannot accept a raw
                    // Throwable, so wrap errors (OOM, etc.) in an Exception.
                    call.reject(
                        "Failed to start server: ${e.message}",
                        e as? Exception ?: RuntimeException(e)
                    )
                    return
                }
            }

            // startForegroundService can throw ForegroundServiceStartNotAllowedException
            // (API 31+) when the host app is not in the foreground. In that case
            // the NanoHTTPD socket is already listening, so we must tear it down
            // before rejecting — otherwise the port stays taken and the JS
            // promise is left in a lying state.
            try {
                HttpServerService.start(
                    appContext(),
                    notifTitle,
                    notifText,
                    channelId,
                    channelName,
                    smallIcon
                )
            } catch (e: Throwable) {
                val srv: LocalHttpServer?
                synchronized(stateLock) {
                    srv = server
                    server = null
                    currentPort = 0
                    isStopping = true
                }
                // A client may have connected in the tiny window between
                // srv.start() and HttpServerService.start(). Drain first so
                // any worker already parked on the latch wakes up, otherwise
                // it would block for the full 60 s request timeout.
                requestBridge.drain(
                    HttpRequestBridge.PendingResponse(
                        status = 503,
                        headers = mapOf("content-type" to "text/plain; charset=utf-8"),
                        bodyText = "Server stopping",
                        bodyBase64 = null,
                        bodyFilePath = null
                    )
                )
                try { srv?.stop() } catch (_: Throwable) {}
                call.reject(
                    "Failed to start foreground service: ${e.message}",
                    e as? Exception ?: RuntimeException(e)
                )
                return
            }

            call.resolve(currentResult())
        } finally {
            synchronized(stateLock) { isStarting = false }
        }
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        val srv: LocalHttpServer?
        synchronized(stateLock) {
            srv = server
            server = null
            currentPort = 0
            isStopping = true
        }

        // Drain pending requests first so blocked worker threads unblock
        // before we tear down the underlying socket. This just flips latches
        // and returns immediately, so it is safe on the main thread.
        requestBridge.drain(
            HttpRequestBridge.PendingResponse(
                status = 503,
                headers = mapOf("content-type" to "text/plain; charset=utf-8"),
                bodyText = "Server stopping",
                bodyBase64 = null,
                bodyFilePath = null
            )
        )

        // NanoHTTPD.stop() calls serverThread.join() and waits for every
        // worker thread to return. Running that on Capacitor's default UI
        // thread produces an ANR (and Android then kills the app) as soon
        // as any worker is still finishing the last write. Push the socket
        // teardown onto a background thread and only resolve the promise
        // once everything is actually closed, so a follow-up start() does
        // not collide with the old socket.
        val ctx = appContext()
        Thread({
            try { srv?.stop() } catch (_: Throwable) {}
            try { HttpServerService.stop(ctx) } catch (_: Throwable) {}
            cleanupTempDir()
            call.resolve()
        }, "HttpServerPlugin-stop").start()
    }

    @PluginMethod
    fun respond(call: PluginCall) {
        val requestId = call.getString("requestId")
        if (requestId.isNullOrEmpty()) {
            call.reject("requestId is required")
            return
        }
        val status = call.getInt("status") ?: 200
        val headersObj = call.getObject("headers")
        val headers = HashMap<String, String>()
        if (headersObj != null) {
            val it = headersObj.keys()
            while (it.hasNext()) {
                val k = it.next()
                headers[k] = headersObj.optString(k, "")
            }
        }
        val bodyText = if (call.data.has("bodyText")) call.getString("bodyText") else null
        val bodyBase64 = if (call.data.has("bodyBase64")) call.getString("bodyBase64") else null
        val bodyFilePath = if (call.data.has("bodyFilePath")) call.getString("bodyFilePath") else null

        val response = HttpRequestBridge.PendingResponse(
            status = status,
            headers = headers,
            bodyText = bodyText,
            bodyBase64 = bodyBase64,
            bodyFilePath = bodyFilePath
        )
        requestBridge.complete(requestId, response)
        call.resolve()
    }

    override fun handleOnDestroy() {
        val srv: LocalHttpServer?
        synchronized(stateLock) {
            srv = server
            server = null
            isStopping = true
        }
        requestBridge.drain(null)
        try { srv?.stop() } catch (_: Throwable) {}
        HttpServerService.stop(appContext())
    }

    // ---------- Internals ----------

    private fun currentResult(): JSObject {
        val ip = localIp()
        return JSObject().apply {
            put("port", currentPort)
            put("url", buildUrl(ip, currentPort))
            put("localIp", ip)
        }
    }

    private fun cleanupTempDir() {
        try {
            tempDir().listFiles()?.forEach { it.delete() }
        } catch (_: Throwable) {}
    }

    private fun buildUrl(ip: String, port: Int): String = "http://$ip:$port"

    private fun pickFreePort(): Int {
        ServerSocket(0).use { socket ->
            socket.reuseAddress = true
            return socket.localPort
        }
    }

    private fun localIp(): String {
        try {
            @Suppress("DEPRECATION")
            val wifi = appContext().applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val raw = wifi?.connectionInfo?.ipAddress ?: 0
            if (raw != 0) {
                val bytes = byteArrayOf(
                    (raw and 0xff).toByte(),
                    (raw shr 8 and 0xff).toByte(),
                    (raw shr 16 and 0xff).toByte(),
                    (raw shr 24 and 0xff).toByte()
                )
                val addr = InetAddress.getByAddress(bytes).hostAddress
                if (!addr.isNullOrEmpty() && addr != "0.0.0.0") return addr
            }
        } catch (_: Throwable) { /* fall through */ }

        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (_: Throwable) { /* ignore */ }

        return "127.0.0.1"
    }

    private fun emitServerError(message: String, fatal: Boolean) {
        val evt = JSObject().apply {
            put("message", message)
            put("fatal", fatal)
        }
        notifyListeners("server-error", evt)
    }

    // ---------- NanoHTTPD implementation ----------

    private inner class LocalHttpServer(port: Int) : NanoHTTPD("0.0.0.0", port) {

        override fun serve(session: IHTTPSession): Response {
            return try {
                handle(session)
            } catch (_: OutOfMemoryError) {
                emitServerError("Out of memory while serving request", false)
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "text/plain",
                    "Out of memory"
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Error handling request", t)
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "text/plain",
                    "Internal error: ${t.message}"
                )
            }
        }

        private fun handle(session: IHTTPSession): Response {
            if (isStopping) {
                return newFixedLengthResponse(
                    Response.Status.SERVICE_UNAVAILABLE,
                    "text/plain",
                    "Server stopping"
                )
            }

            val headers = session.headers // NanoHTTPD already lower-cases header names.

            val transferEncoding = headers["transfer-encoding"]
            if (transferEncoding != null &&
                transferEncoding.contains("chunked", ignoreCase = true)
            ) {
                return newFixedLengthResponse(
                    Response.Status.NOT_IMPLEMENTED,
                    "text/plain",
                    "Chunked transfer encoding is not supported by the server"
                )
            }

            val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
            if (contentLength < 0) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "text/plain",
                    "Invalid Content-Length"
                )
            }
            if (contentLength > maxBodyBytes) {
                return newFixedLengthResponse(
                    Response.Status.PAYLOAD_TOO_LARGE,
                    "text/plain",
                    "Payload exceeds maxBodyBytes"
                )
            }

            val requestId = UUID.randomUUID().toString()
            val contentType = headers["content-type"] ?: ""

            var tempFile: File? = null
            var bodyText: String? = null
            var bodyBase64: String? = null
            var bodyFilePath: String? = null

            if (contentLength > 0L) {
                if (contentLength > fileBodyThresholdBytes) {
                    tempFile = File(tempDir(), "req-$requestId")
                    tempFile.outputStream().use { out ->
                        copyBytes(session.inputStream, out, contentLength)
                    }
                    bodyFilePath = tempFile.absolutePath
                } else {
                    val buf = ByteArray(contentLength.toInt())
                    readFully(session.inputStream, buf)
                    if (isTextLike(contentType)) {
                        bodyText = try {
                            String(buf, Charsets.UTF_8)
                        } catch (_: Throwable) {
                            null
                        }
                        if (bodyText == null) bodyBase64 = Base64.encodeToString(buf, Base64.NO_WRAP)
                    } else {
                        bodyBase64 = Base64.encodeToString(buf, Base64.NO_WRAP)
                    }
                }
            }

            val event = JSObject().apply {
                put("requestId", requestId)
                put("method", session.method.name)
                put("path", session.uri ?: "/")
                put("query", parseQuery(session.queryParameterString))
                put("headers", headersToJson(headers))
                session.headers["remote-addr"]?.let { put("clientIp", it) }
                bodyText?.let { put("bodyText", it) }
                bodyBase64?.let { put("bodyBase64", it) }
                bodyFilePath?.let { put("bodyFilePath", it) }
            }

            val latch = CountDownLatch(1)
            val holder = arrayOf<HttpRequestBridge.PendingResponse?>(null)

            requestBridge.register(
                requestId,
                HttpRequestBridge.Pending(
                    responder = { resp ->
                        holder[0] = resp
                        latch.countDown()
                    },
                    tempFile = tempFile
                )
            )

            // stop() may have drained the bridge *before* we registered; in
            // that case we'd block for 60 s waiting for a response that will
            // never come. Re-check and unregister eagerly if so.
            if (isStopping) {
                val entry = requestBridge.consume(requestId)
                entry?.tempFile?.let { runCatching { it.delete() } }
                return newFixedLengthResponse(
                    Response.Status.SERVICE_UNAVAILABLE,
                    "text/plain",
                    "Server stopping"
                )
            }

            notifyListeners("request", event)

            val completed = latch.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                val entry = requestBridge.consume(requestId)
                entry?.tempFile?.let { runCatching { it.delete() } }
                // NanoHTTPD 2.3.1 does not expose 504 in Response.Status,
                // so build a custom IStatus via HttpRequestBridge.statusOf.
                return newFixedLengthResponse(
                    HttpRequestBridge.statusOf(504),
                    "text/plain",
                    "JS handler did not respond in time"
                )
            }

            val resp = holder[0]
                ?: return newFixedLengthResponse(
                    Response.Status.SERVICE_UNAVAILABLE,
                    "text/plain",
                    "Server shutting down"
                )

            return buildResponse(resp)
        }

        private fun buildResponse(resp: HttpRequestBridge.PendingResponse): Response {
            val status = HttpRequestBridge.statusOf(resp.status)
            val contentType = resp.headers.entries
                .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
                ?.value
                ?: "application/octet-stream"

            val response: Response = when {
                resp.bodyFilePath != null -> {
                    val file = File(resp.bodyFilePath)
                    if (!file.exists() || !file.isFile) {
                        return newFixedLengthResponse(
                            Response.Status.INTERNAL_ERROR,
                            "text/plain",
                            "Response file not found: ${resp.bodyFilePath}"
                        )
                    }
                    val stream: InputStream = FileInputStream(file)
                    newFixedLengthResponse(status, contentType, stream, file.length())
                }
                resp.bodyText != null -> {
                    newFixedLengthResponse(status, contentType, resp.bodyText)
                }
                resp.bodyBase64 != null -> {
                    val bytes = Base64.decode(resp.bodyBase64, Base64.DEFAULT)
                    newFixedLengthResponse(status, contentType, bytes.inputStream(), bytes.size.toLong())
                }
                else -> {
                    newFixedLengthResponse(status, contentType, "")
                }
            }

            // Skip content-type/length/transfer-encoding: NanoHTTPD owns these.
            for ((k, v) in resp.headers) {
                when (k.lowercase(Locale.ROOT)) {
                    "content-type", "content-length", "transfer-encoding" -> continue
                    else -> response.addHeader(k, v)
                }
            }

            return response
        }
    }

    // ---------- Helpers ----------

    private fun copyBytes(input: InputStream, out: OutputStream, total: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = total
        while (remaining > 0) {
            val toRead = if (remaining > buffer.size) buffer.size else remaining.toInt()
            val read = input.read(buffer, 0, toRead)
            if (read == -1) throw IOException("Premature EOF while reading body")
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var read = 0
        while (read < target.size) {
            val n = input.read(target, read, target.size - read)
            if (n == -1) throw IOException("Premature EOF while reading body")
            read += n
        }
    }

    private fun isTextLike(contentType: String): Boolean {
        val ct = contentType.lowercase(Locale.ROOT)
        return ct.startsWith("text/") ||
            ct.startsWith("application/json") ||
            ct.startsWith("application/x-www-form-urlencoded") ||
            ct.startsWith("application/xml") ||
            ct.startsWith("application/javascript")
    }

    private fun parseQuery(raw: String?): JSObject {
        val obj = JSObject()
        if (raw.isNullOrEmpty()) return obj
        for (pair in raw.split("&")) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            val key = if (idx >= 0) pair.substring(0, idx) else pair
            val value = if (idx >= 0) pair.substring(idx + 1) else ""
            try {
                val decodedKey = URLDecoder.decode(key, "UTF-8")
                val decodedValue = URLDecoder.decode(value, "UTF-8")
                obj.put(decodedKey, decodedValue)
            } catch (_: Throwable) {
                obj.put(key, value)
            }
        }
        return obj
    }

    private fun headersToJson(headers: Map<String, String>): JSObject {
        val obj = JSObject()
        for ((k, v) in headers) {
            obj.put(k.lowercase(Locale.ROOT), v)
        }
        return obj
    }

    companion object {
        private const val TAG = "HttpServerPlugin"
        private const val REQUEST_TIMEOUT_SECONDS = 60L
        private const val DEFAULT_MAX_BODY = 50L * 1024L * 1024L
        private const val DEFAULT_FILE_THRESHOLD = 1L * 1024L * 1024L
    }
}
