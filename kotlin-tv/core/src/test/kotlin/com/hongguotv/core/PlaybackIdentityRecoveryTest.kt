package com.hongguotv.core

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Regression: search registration must not replace the working 0.9.0 playback context. */
class PlaybackIdentityRecoveryTest {
    private fun isolated(action: ()->Unit) = synchronized(DeviceIdentity) {
        val install=DeviceIdentity.installId;val device=DeviceIdentity.deviceId;val registered=DeviceIdentity.registered
        try {DeviceIdentity.apply("111","222");action()}finally {DeviceIdentity.apply(install,device);DeviceIdentity::class.java.getDeclaredField("registeredValue").apply {isAccessible=true}.setBoolean(DeviceIdentity,registered)}
    }
    @Test fun searchRegistrationCannotChangePlaybackIdentityOrSignatures() = isolated {
        val playback=VendorConstants.playbackDevice
        val url="https://example.invalid/api?video=123"
        fun signature()=Signer(clock={1800500000000L},random={0.25},deviceValues={VendorConstants.playbackDevice}).sign(url.substringBefore('?' ),VendorConstants.playbackDevice,"{}".toByteArray()).headers
        val original=signature()
        DeviceIdentity.apply("333","444")
        assertEquals(playback,VendorConstants.playbackDevice)
        assertEquals(original,signature())
        assertEquals("444",VendorConstants.device.getValue("device_id"))
    }
    @Test fun emptyPlaybackNeverRotatesOrPersistsSearchIdentity() = isolated {
        var registrations=0;var persisted=0;var requests=0
        val repo=ContentRepository(OkHttpClient.Builder().addInterceptor {chain->
            if(chain.request().url.encodedPath.contains("device_register")) registrations++
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("".toResponseBody()).build()
        }.build())
        repo.onDeviceRegistration={persisted++}
        repeat(4){assertThrows(IOException::class.java){repo.stream("123")}}
        assertEquals(4,requests);assertEquals(0,registrations);assertEquals(0,persisted)
        assertEquals("222",DeviceIdentity.deviceId)
    }
    @Test fun signedQueryRemainsOnPlaybackContextAfterSearchIdentityChanges() = isolated {
        val expected=VendorConstants.playbackDevice
        val repo=ContentRepository(OkHttpClient.Builder().addInterceptor {chain->
            assertEquals(expected.getValue("device_id"),chain.request().url.queryParameter("device_id"))
            assertEquals(expected.getValue("iid"),chain.request().url.queryParameter("iid"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":0,"data":{"123":{"video_model":{"video_list":[{"main_url":"https://example.invalid/video.mp4","height":720}]}}}}""".toResponseBody()).build()
        }.build())
        repo.stream("123")
        DeviceIdentity.apply("333","444")
        repo.stream("123")
        assertEquals("444",DeviceIdentity.deviceId)
    }
}
