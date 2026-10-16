package com.ttdk1507d.inspectioncamera.api

import org.junit.Assert.*
import org.junit.Test

class ApiClientTest {

    @Test
    fun testGetServiceReturnsNonNull() {
        val service = ApiClient.getService("http://127.0.0.1:8095")
        assertNotNull(service)
    }

    @Test
    fun testGetServiceCachingSameUrl() {
        val s1 = ApiClient.getService("http://192.168.193.11:8095")
        val s2 = ApiClient.getService("http://192.168.193.11:8095")
        assertSame(s1, s2)
    }

    @Test
    fun testGetServiceRecreatesOnDifferentUrl() {
        val s1 = ApiClient.getService("http://192.168.193.11:8095")
        val s2 = ApiClient.getService("http://100.81.114.84:8095")
        assertNotSame(s1, s2)
    }

    @Test
    fun testGetServiceNormalizesTrailingSlash() {
        val s1 = ApiClient.getService("http://10.0.0.1:8080")
        val s2 = ApiClient.getService("http://10.0.0.1:8080/")
        assertSame(s1, s2)
    }
}
