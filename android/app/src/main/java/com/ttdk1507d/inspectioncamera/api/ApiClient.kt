package com.ttdk1507d.inspectioncamera.api

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    private var retrofit: Retrofit? = null
    private var currentBaseUrl: String = ""
    private var apiService: ApiService? = null

    @Synchronized
    fun getService(baseUrl: String): ApiService {
        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        if (retrofit == null || currentBaseUrl != normalizedUrl) {
            currentBaseUrl = normalizedUrl
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

            retrofit = Retrofit.Builder()
                .baseUrl(normalizedUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            apiService = retrofit!!.create(ApiService::class.java)
        }
        return apiService!!
    }
}
