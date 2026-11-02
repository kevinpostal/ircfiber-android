package com.kevinpostal.fiberirc.data.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadersTest {
    @Test
    fun modelIsSanitizedForTheServer() {
        assertEquals("Google_Pixel_8_Pro", deviceModel("Google", "Pixel 8 Pro"))
        assertEquals("samsung_SM-S918B", deviceModel("samsung", "SM-S918B"))
        assertEquals("VeryLongManufacturerName_With_A", deviceModel("VeryLongManufacturerName", "With A Very Long Model Name (5G)"))
        assertEquals("Android", deviceModel(null, null))
    }

    @Test
    fun osVersionKeepsDigitsAndDots() {
        assertEquals("15", deviceOsVersion("15 QPR", 35))
        assertEquals("14.0.1", deviceOsVersion("14.0.1", 34))
        assertEquals("35", deviceOsVersion("Baklava", 35))
    }

    @Test
    fun originOmitsDefaultPort() {
        assertEquals("https://ircfiber.com", originOf("https://ircfiber.com".toHttpUrl()))
        assertEquals("http://127.0.0.1:8090", originOf("http://127.0.0.1:8090/x".toHttpUrl()))
    }

    @Test
    fun originOnlyOnWrites() {
        val server = MockWebServer()
        server.start()
        val base = server.url("/").toString()
        val client = OkHttpClient.Builder().addInterceptor(fiberIrcHeaderInterceptor({ "id-1" }, base)).build()
        server.enqueue(MockResponse())
        server.enqueue(MockResponse())
        client.newCall(Request.Builder().url(server.url("/api/me")).build()).execute().close()
        client.newCall(Request.Builder().url(server.url("/login")).post("".toRequestBody("text/plain".toMediaType())).build()).execute().close()
        val get = server.takeRequest()
        assertNull(get.getHeader("Origin"))
        assertEquals("id-1", get.getHeader(DEVICE_HEADER))
        assertEquals("FiberIRC Android", get.getHeader("User-Agent"))
        assertEquals("application/json", get.getHeader("Accept"))
        assertEquals(java.time.ZoneId.systemDefault().id, get.getHeader(DEVICE_TZ_HEADER))
        assertEquals(originOf(server.url("/")), server.takeRequest().getHeader("Origin"))
        server.shutdown()
    }

    @Test
    fun timeZoneHeaderIsReadPerRequest() {
        val server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().addInterceptor(fiberIrcHeaderInterceptor({ "id-1" }, server.url("/").toString())).build()
        val original = java.util.TimeZone.getDefault()
        try {
            for (zone in listOf("Europe/Berlin", "America/Los_Angeles")) {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone))
                server.enqueue(MockResponse())
                client.newCall(Request.Builder().url(server.url("/api/me")).build()).execute().close()
                assertEquals(zone, server.takeRequest().getHeader(DEVICE_TZ_HEADER))
            }
        } finally {
            java.util.TimeZone.setDefault(original)
            server.shutdown()
        }
    }
}
