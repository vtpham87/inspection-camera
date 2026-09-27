package com.ttdk1507d.inspectioncamera.api

import com.ttdk1507d.inspectioncamera.model.Vehicle
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

@JvmSuppressWildcards
interface ApiService {
    @GET("/api/health")
    suspend fun health(): Response<Map<String, Any>>

    @Multipart
    @POST("/api/upload")
    suspend fun uploadPhoto(
        @Part file: MultipartBody.Part,
        @Part("plate") plate: RequestBody,
        @Part("plate_color") plateColor: RequestBody?,
        @Part("photo_type") photoType: RequestBody,
        @Part("seq") seq: RequestBody?
    ): Response<Map<String, Any>>

    @HTTP(method = "DELETE", path = "/api/photos", hasBody = true)
    suspend fun deletePhoto(@Body body: Map<String, Any?>): Response<Map<String, Any>>

    @GET("/api/vehicles/today")
    suspend fun getVehiclesToday(@Query("date") date: String? = null): Response<List<Vehicle>>

    @GET("/api/config")
    suspend fun getConfig(): Response<Map<String, Any>>

    @POST("/api/config")
    suspend fun postConfig(@Body config: Map<String, Any>): Response<Map<String, Any>>

    @POST("/api/config")
    suspend fun saveConfig(@Body config: com.ttdk1507d.inspectioncamera.model.AppConfig): Response<Map<String, Any>>
}
