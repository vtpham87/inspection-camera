package com.ttdk1507d.inspectioncamera.util

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetSocketAddress

class NetworkUtilTest {

    private var server: HttpServer? = null
    private val tailscaleUrl = "http://100.81.114.84:8095"

    @After
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    @Test
    fun testResolveBaseUrl_lanReachable() = runBlocking {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/health") { exchange ->
                val response = """{"status":"ok"}""".toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }

        val lanPort = server!!.address.port
        val lanUrl = "http://127.0.0.1:$lanPort"

        val resolved = NetworkUtil.resolveBaseUrl(lanUrl, tailscaleUrl)
        assertEquals(lanUrl, resolved)
    }

    @Test
    fun testResolveBaseUrl_lanReturns500() = runBlocking {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/health") { exchange ->
                val response = """{"detail":"Internal error"}""".toByteArray()
                exchange.sendResponseHeaders(500, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }

        val lanPort = server!!.address.port
        val lanUrl = "http://127.0.0.1:$lanPort"

        val resolved = NetworkUtil.resolveBaseUrl(lanUrl, tailscaleUrl)
        assertEquals(tailscaleUrl, resolved)
    }

    @Test
    fun testResolveBaseUrl_lanServerDown() = runBlocking {
        // Start and immediately stop to get an unused closed port
        val dummy = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val closedPort = dummy.address.port
        dummy.stop(0)

        val lanUrl = "http://127.0.0.1:$closedPort"
        val resolved = NetworkUtil.resolveBaseUrl(lanUrl, tailscaleUrl)
        assertEquals(tailscaleUrl, resolved)
    }

    @Test
    fun testResolveBaseUrl_lanWithTrailingSlash() = runBlocking {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/health") { exchange ->
                val response = """{"status":"ok"}""".toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }

        val lanPort = server!!.address.port
        val lanUrlWithSlash = "http://127.0.0.1:$lanPort/"

        val resolved = NetworkUtil.resolveBaseUrl(lanUrlWithSlash, tailscaleUrl)
        assertEquals(lanUrlWithSlash, resolved)
    }

    @Test
    fun testResolveBaseUrl_invalidUrl() = runBlocking {
        val resolved = NetworkUtil.resolveBaseUrl("not-a-valid-url", tailscaleUrl)
        assertEquals(tailscaleUrl, resolved)
    }
}
