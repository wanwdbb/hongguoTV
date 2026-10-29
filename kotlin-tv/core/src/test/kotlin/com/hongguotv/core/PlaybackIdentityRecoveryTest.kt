package com.hongguotv.core

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PlaybackIdentityRecoveryTest {
    private val media="""{"code":0,"data":{"123":{"video_model":{"video_list":[{"main_url":"https://example.invalid/video.mp4","video_meta":{"vheight":720,"codec_type":"h264"}}]}}}}"""
    private fun repository(reply: (Request)->String)=ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(reply(chain.request()).toResponseBody()).build()
    }.build())
    private fun isolated(action: ()->Unit) = synchronized(DeviceIdentity) {
        val install=DeviceIdentity.installId;val device=DeviceIdentity.deviceId
        try { DeviceIdentity.apply("111","222");action() } finally { DeviceIdentity.apply(install,device) }
    }
    @Test fun emptyRoutesRegisterResignPersistAndRetainAccount() = isolated {
        var registrations=0;var saved: DeviceRegistration?=null
        val repo=repository { request ->
            if(request.url.encodedPath.contains("device_register")) {
                registrations++; """{"install_id":"333","device_id":"444"}"""
            } else {
                assertEquals("fixture",request.header("X-Tt-Token"))
                if(request.url.queryParameter("device_id")=="222") "" else media
            }
        }
        val session=AuthSession(emptyMap(),"fixture",AuthUser("fixture","fixture",accountId="123"))
        repo.restoreSession(session);repo.onDeviceRegistration={ saved=it }
        assertEquals("https://example.invalid/video.mp4",repo.stream("123").url)
        assertEquals(1,registrations);assertEquals("444",saved?.deviceId)
        assertEquals(session,repo.currentSession())
        repo.stream("123");assertEquals(1,registrations)
    }
    @Test fun persistentEmptyIsBoundedAcrossPlayerRetries() = isolated {
        var registrations=0;var requests=0
        val repo=repository { request ->
            if(request.url.encodedPath.contains("device_register")) {
                registrations++; """{"install_id":"333","device_id":"444"}"""
            } else { requests++;"" }
        }
        repeat(4) { assertThrows(IOException::class.java) { repo.stream("123") } }
        assertEquals(2,registrations);assertEquals(12,requests)
        assertEquals("222",DeviceIdentity.deviceId)
    }
    @Test fun registrationFailureKeepsIdentityAndDoesNotLoop() = isolated {
        var registrations=0
        val repo=repository { request ->
            if(request.url.encodedPath.contains("device_register")) { registrations++;"{}" } else ""
        }
        repeat(2) { assertThrows(IOException::class.java) { repo.stream("123") } }
        assertEquals(1,registrations);assertEquals("222",DeviceIdentity.deviceId)
    }
    @Test fun networkFailureDoesNotRotateIdentity() = isolated {
        var registrations=0
        val repo=repository { request ->
            if(request.url.encodedPath.contains("device_register")) registrations++
            throw IOException("offline")
        }
        assertThrows(IOException::class.java) { repo.stream("123") }
        assertEquals(0,registrations);assertEquals("222",DeviceIdentity.deviceId)
    }
    @Test fun firstRegisteredIdentityStillEmptySecondWorksAndOnlySuccessIsSaved() = isolated {
        var registrations=0
        val saved=mutableListOf<String>()
        val repo=repository { request ->
            if(request.url.encodedPath.contains("device_register")) {
                registrations++
                """{"install_id":"333","device_id":"${if(registrations==1) "444" else "555"}"}"""
            } else if(request.url.queryParameter("device_id")=="555") media else ""
        }
        repo.onDeviceRegistration={ saved+=it.deviceId }
        assertEquals("https://example.invalid/video.mp4",repo.stream("123").url)
        assertEquals(2,registrations);assertEquals(listOf("555"),saved)
        repo.stream("123");assertEquals(2,registrations)
    }
    @Test fun invalidModelNeverPersistsCandidateIdentity() = isolated {
        val repo=repository { request ->
            when {
                request.url.encodedPath.contains("device_register") -> """{"install_id":"333","device_id":"444"}"""
                request.url.queryParameter("device_id")=="222" -> ""
                else -> """{"code":0,"data":{}}"""
            }
        }
        var saved=false;repo.onDeviceRegistration={ saved=true }
        assertThrows(IOException::class.java) { repo.stream("123") }
        assertFalse(saved);assertEquals("222",DeviceIdentity.deviceId)
    }
}
