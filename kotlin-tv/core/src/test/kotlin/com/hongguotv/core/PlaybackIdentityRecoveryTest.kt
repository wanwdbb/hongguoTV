package com.hongguotv.core
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PlaybackIdentityRecoveryTest {
    @Test fun sharedRegisteredIdentitySurvivesSearchChangesWithoutPersistingIt() {
        var registrations=0; var persisted=0
        val session=PlaybackSession { _,_ -> registrations++; DeviceRegister.Registered("333","444") }
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("333",chain.request().url.queryParameter("device_id"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":{"123":{"video_model":{"video_list":[{"main_url":"https://example.invalid/a.mp4","height":720}]}}}}""".toResponseBody()).build()
        }.build()
        val before=DeviceIdentity.deviceId
        repeat(2) {
            val repo=ContentRepository(client,playbackSession=session)
            repo.onDeviceRegistration={persisted++}; repo.stream("123")
        }
        assertEquals(1,registrations); assertEquals(0,persisted); assertEquals(before,DeviceIdentity.deviceId)
    }
    @Test fun failedRegistrationDoesNotSubmitAnUnregisteredPlaybackRequest() {
        var requests=0
        val client=OkHttpClient.Builder().addInterceptor { requests++; throw IOException("should not send") }.build()
        val repo=ContentRepository(client,playbackSession=PlaybackSession { _,_ -> null })
        assertThrows(PlaybackRegistrationException::class.java) { repo.stream("123") }
        assertEquals(0,requests)
    }
    @Test fun cancelledScopeNeverRegistersOrFetches() {
        var registrations=0
        val repo=ContentRepository(playbackSession=PlaybackSession { _,_ -> registrations++; null })
        val scope=RequestScope(); scope.cancel()
        assertThrows(IOException::class.java) { scope.run { repo.stream("123") } }
        assertEquals(0,registrations)
    }
    @Test fun backgroundFailureUsesOneCandidateAndNoIdentityRotation() {
        var requests=0
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("".toResponseBody()).build()
        }.build()
        val repo=ContentRepository(client,playbackSession=playbackFixture())
        val error=assertThrows(PlaybackModelException::class.java) { repo.stream("123",speculative=true) }
        assertEquals(1,requests); assertEquals("GET:0=200/0B",error.diagnostic)
        assertFalse(error.diagnostic.contains("device_id"))
    }

    @Test fun fullyEmptyForegroundRoundRecoversWithinSameAttempt() {
        var registrations=0
        val session=PlaybackSession { _,_ -> registrations++; DeviceRegister.Registered(if(registrations==1) "333" else "555","444") }
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            val body=if(chain.request().url.queryParameter("device_id")=="333") "" else
                """{"data":{"123":{"video_model":{"video_list":[{"main_url":"https://example.invalid/a.mp4","height":720}]}}}}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        val repo=ContentRepository(client,playbackSession=session)
        repo.stream("123")
        assertEquals(2,registrations)
    }
    @Test fun failedRecoveryPreservesProfileAndSharesShortCooldown() {
        var registrations=0
        val session=PlaybackSession { _,_ -> registrations++; if(registrations==1) DeviceRegister.Registered("333","444") else null }
        val http=OkHttpClient(); val initial=session.profile(http)
        assertSame(initial,session.recover(http,initial,1000))
        assertSame(initial,session.profile(http))
        assertSame(initial,session.recover(http,initial,5999))
        assertEquals(2,registrations)
        session.recover(http,initial,6000)
        assertEquals(3,registrations)
    }
    @Test fun concurrentStaleRecoveryReusesReplacementWithoutRegisteringAgain() {
        var registrations=0
        val session=PlaybackSession { _,_ -> registrations++; DeviceRegister.Registered("333","444") }
        val http=OkHttpClient(); val initial=session.profile(http)
        val replacement=session.recover(http,initial,1000)
        assertNotSame(initial,replacement)
        assertSame(replacement,session.recover(http,initial,1001))
        assertEquals(2,registrations)
    }

    @Test fun manualAndPreloadRepositoriesShareSuccessfulRouteForSameProfile() {
        val methods=mutableListOf<String>()
        val session=playbackFixture()
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request(); methods+=request.method
            val reply=if(request.method=="GET") "" else
                """{"data":{"123":{"video_model":{"video_list":[{"main_url":"https://example.invalid/a.mp4","height":720}]}}}}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(reply.toResponseBody()).build()
        }.build()
        ContentRepository(http,playbackSession=session).stream("123")
        ContentRepository(http,playbackSession=session).stream("123",speculative=true)
        ContentRepository(http,playbackSession=session).stream("123")
        assertEquals(listOf("GET","POST","POST","POST"),methods)
    }
    @Test fun backgroundDoesNotProbeAllCoolingRoutesOrRotateIdentity() {
        var registrations=0; var requests=0
        val session=PlaybackSession { _,_ -> registrations++; DeviceRegister.Registered("333","444") }
        val http=OkHttpClient.Builder().addInterceptor { chain -> requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("".toResponseBody()).build()
        }.build()
        val repo=ContentRepository(http,playbackSession=session)
        assertThrows(PlaybackModelException::class.java) { repo.stream("123") }
        assertEquals(5,requests); assertEquals(2,registrations)
        // 新会话只探测过 GET，逐一冷却其三个 POST；完全冷却后后台不能再碰网络。
        repeat(3) { assertThrows(PlaybackModelException::class.java) { repo.stream("123",speculative=true) } }
        val before=requests
        assertThrows(PlaybackModelException::class.java) { ContentRepository(http,playbackSession=session).stream("123",speculative=true) }
        assertEquals(before,requests); assertEquals(2,registrations)
    }
    @Test fun foregroundStillProbesOnePostWhenOnlyGetHasRecoveredFromCooldown() {
        val routes=PlaybackRoutes()
        routes.routes.forEach { routes.mark(it,1000,false,true) }
        routes.mark(routes.routes.first(),2000,true)
        val plan=routes.plan(2001,false)
        assertEquals(2,plan.size); assertTrue(plan[0].get); assertFalse(plan[1].get)
        assertEquals(listOf(plan[0]),routes.plan(2001,true))
    }
    @Test fun successfulRecoveryCooldownIsSharedAcrossCallers() {
        var registrations=0
        val session=PlaybackSession { _,_ -> registrations++; DeviceRegister.Registered("333","444") }
        val http=OkHttpClient(); val first=session.profile(http)
        val second=session.recover(http,first,1000)
        assertSame(second,session.recover(http,second,30999))
        assertEquals(2,registrations)
        assertNotSame(second,session.recover(http,second,31000))
        assertEquals(3,registrations)
    }
}
