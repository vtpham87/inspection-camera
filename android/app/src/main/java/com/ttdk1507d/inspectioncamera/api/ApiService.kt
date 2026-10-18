package com.ttdk1507d.inspectioncamera.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.HTTP
import retrofit2.http.POST

@JvmSuppressWildcards
interface ApiService {
    @HTTP(method = "DELETE", path = "/api/photos", hasBody = true)
    suspend fun deletePhoto(@Body body: Map<String, Any?>): Response<Map<String, Any>>

    @POST("/api/config")
    suspend fun postConfig(@Body config: Map<String, Any>): Response<Map<String, Any>>
}
