package com.cantooscribe.httpserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HttpRequestBridgeTest {

    @Test
    fun registerAndConsume() {
        val bridge = HttpRequestBridge()
        var got: HttpRequestBridge.PendingResponse? = null
        bridge.register(
            "abc",
            HttpRequestBridge.Pending(
                responder = { got = it },
                tempFile = null
            )
        )
        assertEquals(1, bridge.size())

        val response = HttpRequestBridge.PendingResponse(200, emptyMap(), "ok", null, null)
        bridge.complete("abc", response)
        assertEquals(0, bridge.size())
        assertNotNull(got)
        assertEquals("ok", got!!.bodyText)
    }

    @Test
    fun completeUnknownIsNoOp() {
        val bridge = HttpRequestBridge()
        bridge.complete("missing", null)
        assertEquals(0, bridge.size())
    }

    @Test
    fun drainClearsAll() {
        val bridge = HttpRequestBridge()
        var a: HttpRequestBridge.PendingResponse? = null
        var b: HttpRequestBridge.PendingResponse? = null
        bridge.register("a", HttpRequestBridge.Pending({ a = it }, null))
        bridge.register("b", HttpRequestBridge.Pending({ b = it }, null))
        val drained = HttpRequestBridge.PendingResponse(503, emptyMap(), "bye", null, null)
        bridge.drain(drained)
        assertEquals(0, bridge.size())
        assertEquals("bye", a?.bodyText)
        assertEquals("bye", b?.bodyText)
        assertNull(bridge.consume("a"))
    }
}
