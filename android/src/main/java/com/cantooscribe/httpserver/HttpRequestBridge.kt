package com.cantooscribe.httpserver

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks pending requests that are waiting for a JS-side response.
 *
 * Each entry is keyed by an opaque request UUID. The responder callback is
 * invoked by [HttpServerPlugin.respond] with either the JS response envelope
 * or `null` when the request was cancelled or timed out.
 */
internal class HttpRequestBridge {

    data class Pending(
        val responder: (PendingResponse?) -> Unit,
        /** Temp file holding the uploaded body, if any. Deleted on completion. */
        val tempFile: File?
    )

    /** JS response envelope forwarded back to the HTTP thread. */
    data class PendingResponse(
        val status: Int,
        val headers: Map<String, String>,
        val bodyText: String?,
        val bodyBase64: String?,
        val bodyFilePath: String?
    )

    private val pending = ConcurrentHashMap<String, Pending>()

    fun register(id: String, entry: Pending) {
        pending[id] = entry
    }

    /**
     * Remove and return the entry for [id], or `null` if it was already consumed
     * (e.g. by a timeout or a prior respond call).
     */
    fun consume(id: String): Pending? = pending.remove(id)

    /** Complete [id] with [response]. Safe to call after the request was consumed. */
    fun complete(id: String, response: PendingResponse?) {
        val entry = consume(id) ?: return
        try {
            entry.responder(response)
        } finally {
            entry.tempFile?.let { runCatching { it.delete() } }
        }
    }

    /** Drain all pending requests, completing each one with [response]. */
    fun drain(response: PendingResponse?) {
        val snapshot = ArrayList(pending.keys)
        for (id in snapshot) {
            complete(id, response)
        }
    }

    fun size(): Int = pending.size

    companion object {
        fun statusOf(code: Int): NanoHTTPD.Response.IStatus {
            // Prefer built-in status codes for accurate text; fall back to a custom one.
            val builtin = NanoHTTPD.Response.Status.values().firstOrNull { it.requestStatus == code }
            return builtin ?: object : NanoHTTPD.Response.IStatus {
                override fun getDescription(): String = "$code Custom"
                override fun getRequestStatus(): Int = code
            }
        }
    }
}
