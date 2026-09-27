package com.ttdk1507d.inspectioncamera.api

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class ApiServiceTest {

    private var server: HttpServer? = null
    private lateinit var apiService: ApiService
    private var port: Int = 0

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server!!.address.port
        apiService = ApiClient.getService("http://127.0.0.1:$port")
    }

    @After
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    @Test
    fun testHealthEndpoint() = runBlocking {
        server!!.createContext("/api/health") { exchange ->
            assertEquals("GET", exchange.requestMethod)
            val json = """{"status":"ok","app":"inspection-camera-sync","version":"1.0.0"}"""
            val bytes = json.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server!!.start()

        val response = apiService.health()
        assertTrue(response.isSuccessful)
        val body = response.body()
        assertNotNull(body)
        assertEquals("ok", body!!["status"])
        assertEquals("inspection-camera-sync", body["app"])
    }

    @Test
    fun testGetVehiclesTodayEndpoint() = runBlocking {
        server!!.createContext("/api/vehicles/today") { exchange ->
            assertEquals("GET", exchange.requestMethod)
            assertTrue(exchange.requestURI.query?.contains("date=2026-09-27") == true)
            val json = """
                [
                    {
                        "plate": "15A-123.45",
                        "plate_clean": "15A12345",
                        "plate_color": null,
                        "vehicle_type": "Ô tô con",
                        "brand": "Toyota",
                        "owner": "Nguyễn Văn A",
                        "time": "08:30",
                        "result": 1,
                        "photos_taken": ["front_45"]
                    }
                ]
            """.trimIndent()
            val bytes = json.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server!!.start()

        val response = apiService.getVehiclesToday("2026-09-27")
        assertTrue(response.isSuccessful)
        val vehicles = response.body()
        assertNotNull(vehicles)
        assertEquals(1, vehicles!!.size)
        val v = vehicles[0]
        assertEquals("15A-123.45", v.plate)
        assertEquals("15A12345", v.plateClean)
        assertNull(v.plateColor)
        assertEquals("Toyota", v.brand)
        assertEquals(1, v.result)
        assertEquals(listOf("front_45"), v.photosTaken)
    }

    @Test
    fun testGetConfigEndpoint() = runBlocking {
        server!!.createContext("/api/config") { exchange ->
            assertEquals("GET", exchange.requestMethod)
            val json = """{"center_code":"1507D","save_dir":"D:/Photos"}"""
            val bytes = json.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server!!.start()

        val response = apiService.getConfig()
        assertTrue(response.isSuccessful)
        val body = response.body()
        assertNotNull(body)
        assertEquals("1507D", body!!["center_code"])
        assertEquals("D:/Photos", body["save_dir"])
    }

    @Test
    fun testPostConfigEndpoint() = runBlocking {
        server!!.createContext("/api/config") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            val json = """{"success":true,"saved":true}"""
            val bytes = json.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server!!.start()

        val response = apiService.postConfig(mapOf("center_code" to "1507D"))
        assertTrue(response.isSuccessful)
        val body = response.body()
        assertNotNull(body)
        assertEquals(true, body!!["success"])
    }

    @Test
    fun testDeletePhotoEndpoint() = runBlocking {
        server!!.createContext("/api/photos") { exchange ->
            assertEquals("DELETE", exchange.requestMethod)
            val json = """{"success":true,"message":"Deleted"}"""
            val bytes = json.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server!!.start()

        val response = apiService.deletePhoto(mapOf("plate" to "15A12345", "photo_type" to "front_45"))
        assertTrue(response.isSuccessful)
        val body = response.body()
        assertNotNull(body)
        assertEquals(true, body!!["success"])
    }

    @Test
    fun testUploadPhotoEndpoint() = runBlocking {
        server!!.createContext("/api/upload") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            val json = """{"success":true,"saved_path":"D:/Photos/15A12345_bs.jpg"}"""
            val bytes = json.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server!!.start()

        val filePart = MultipartBody.Part.createFormData(
            "file",
            "photo.jpg",
            "dummy-image-bytes".toByteArray().toRequestBody("image/jpeg".toMediaTypeOrNull())
        )
        val textType = "text/plain".toMediaTypeOrNull()
        val plate = "15A12345".toRequestBody(textType)
        val photoType = "front_45".toRequestBody(textType)

        val response = apiService.uploadPhoto(
            file = filePart,
            plate = plate,
            plateColor = null,
            photoType = photoType,
            seq = null
        )
        assertTrue(response.isSuccessful)
        val body = response.body()
        assertNotNull(body)
        assertEquals(true, body!!["success"])
        assertEquals("D:/Photos/15A12345_bs.jpg", body["saved_path"])
    }
}
