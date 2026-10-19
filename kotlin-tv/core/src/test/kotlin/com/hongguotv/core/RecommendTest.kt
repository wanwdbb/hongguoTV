package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class RecommendTest {
    private val types=listOf(CatalogType.REAL,CatalogType.COMIC_DRAMA,CatalogType.AI_DRAMA)
    private fun candidate(id: Long,type: CatalogType,tags: String = "玄幻 · 逆袭",discovery: Boolean = false) =
        RecommendCandidate(Series("$id","剧集 $id","","","全 20 集",tags),type,discovery)
    private fun pool(perType: Int,type: CatalogType,tags: String = "玄幻 · 逆袭",discovery: Boolean = false,start: Long = 1) =
        (start until start+perType).map { candidate(it,type,tags,discovery) }
    private fun candidates(perType: Int,discovery: Boolean = false,tags: String = "玄幻 · 逆袭") =
        types.flatMapIndexed { index,type -> pool(perType,type,tags=tags,discovery=discovery,start=1L+index*1000) }

    @Test fun mixedBatchKeepsDocumentedTypeMix() {
        val batch=RecommendSession("s1",RecommendMode.MIXED).append(candidates(40))
        assertEquals(24,batch.size)
        assertEquals(12,batch.count { it.type==CatalogType.REAL })
        assertEquals(7,batch.count { it.type==CatalogType.COMIC_DRAMA })
        assertEquals(5,batch.count { it.type==CatalogType.AI_DRAMA })
    }

    @Test fun weightsStayAtFiftyThirtyTwentyWithoutTypeSignals() {
        val weights=RecommendSession("s1",RecommendMode.MIXED).weights
        assertEquals(0.5,weights.getValue(CatalogType.REAL),0.001)
        assertEquals(0.3,weights.getValue(CatalogType.COMIC_DRAMA),0.001)
        assertEquals(0.2,weights.getValue(CatalogType.AI_DRAMA),0.001)
    }

    @Test fun typeInterestsShiftMixButKeepEveryTypeAboveTenPercent() {
        val profile=RecommendProfile(typeInterests=mapOf(CatalogType.REAL to 100))
        val batch=RecommendSession("s1",RecommendMode.MIXED,profile).append(candidates(40))
        val counts=batch.groupingBy { it.type }.eachCount()
        assertTrue((counts[CatalogType.REAL] ?: 0)>=12)
        assertTrue((counts[CatalogType.COMIC_DRAMA] ?: 0)>=2)
        assertTrue((counts[CatalogType.AI_DRAMA] ?: 0)>=2)
    }

    @Test fun explorationReservesTwentyPercentForUnfamiliarTitles() {
        val familiar=candidates(40,tags="玄幻")
        val discovery=types.flatMapIndexed { index,type -> pool(4,type,tags="悬疑",discovery=true,start=5000L+index*1000) }
        val profile=RecommendProfile(interests=mapOf("玄幻" to 6))
        val batch=RecommendSession("s1",RecommendMode.MIXED,profile).append(discovery+familiar)
        assertEquals(24,batch.size)
        assertEquals(5,batch.count { it.discovery })
        assertEquals("reserved slots come before familiar titles",19,batch.count { !it.discovery })
    }

    @Test fun completedAndRecentlyWatchedAreNotRecommended() {
        val profile=RecommendProfile(completed=setOf("99"),recent=mapOf("1" to System.currentTimeMillis()-60_000))
        val batch=RecommendSession("s1",RecommendMode.MIXED,profile,now=System.currentTimeMillis()).append(pool(12,CatalogType.REAL)+candidate(99,CatalogType.REAL),limit=11)
        assertEquals(11,batch.size)
        assertFalse(batch.any { it.series.id=="1" })
        assertFalse(batch.any { it.series.id=="99" })
    }

    @Test fun paginationDoesNotRepeatAcrossBatches() {
        val session=RecommendSession("s1",RecommendMode.MIXED)
        val all=candidates(60)
        val first=session.append(all)
        val second=session.append(all)
        assertEquals(24,first.size)
        assertEquals(24,second.size)
        assertEquals(24,first.map { it.series.id }.distinct().size)
        assertTrue(first.map { it.series.id }.toSet().intersect(second.map { it.series.id }.toSet()).isEmpty())
    }

    @Test fun newSessionReshufflesTheFeed() {
        val all=candidates(40)
        val first=RecommendSession("a",RecommendMode.MIXED).append(all).map { it.series.id }
        val second=RecommendSession("b",RecommendMode.MIXED).append(all).map { it.series.id }
        assertEquals(24,second.size)
        assertNotEquals(first,second)
    }

    @Test fun singleTypeModeOnlyReturnsThatType() {
        val session=RecommendSession("s1",RecommendMode.COMIC)
        val batch=session.append(candidates(40))
        assertEquals(24,batch.size)
        assertTrue(batch.all { it.type==CatalogType.COMIC_DRAMA })
    }

    @Test fun missingTypeIsToppedUpByAvailableTypes() {
        val batch=RecommendSession("s1",RecommendMode.MIXED).append(pool(30,CatalogType.REAL))
        assertEquals(24,batch.size)
        assertTrue(batch.all { it.type==CatalogType.REAL })
    }

    @Test fun emptyOrInvalidCandidatesNeverThrow() {
        val mixed=RecommendSession("s1",RecommendMode.MIXED)
        assertTrue(mixed.append(emptyList()).isEmpty())
        assertTrue(mixed.append(listOf(RecommendCandidate(Series("abc","剧集"),CatalogType.REAL),RecommendCandidate(Series("7",""),CatalogType.REAL))).isEmpty())
        assertTrue(RecommendSession("s2",RecommendMode.AI).append(pool(5,CatalogType.COMIC)).isEmpty())
        assertEquals(5,mixed.append(pool(5,CatalogType.REAL)).size)
    }

    @Test fun duplicateCandidatesShareOneSlot() {
        val batch=RecommendSession("s1",RecommendMode.MIXED).append(pool(5,CatalogType.REAL)+pool(5,CatalogType.REAL),limit=10)
        assertEquals(5,batch.size)
    }

    @Test fun favoriteCountsFourAndThirtySecondsOfRealPlaybackCountsOne() {
        val favorite=Series("1","收藏剧","","","","玄幻 · 逆袭")
        val history=listOf(
            BackupProgress(Series("2","看过剧","","","","玄幻 · 甜宠"),"e",0,30_000,600_000,false,1_000),
            BackupProgress(Series("3","刚点开","","","","玄幻 · 甜宠"),"e",0,29_999,600_000,false,1_000)
        )
        val profile=RecommendProfile.of(listOf(favorite),history,emptySet(),now=2_000)
        assertEquals(5,profile.interest("玄幻"))
        assertEquals(4,profile.interest("逆袭"))
        assertEquals(1,profile.interest("甜宠"))
        assertTrue(profile.isRecent("2",2_000))
    }

    @Test fun completedSeriesIsRecognisedFromFullEpisodeBadge() {
        val row=BackupProgress(Series("9","完结剧","","","全 12 集","玄幻"),"e",11,600_000,600_000,true,1_000)
        val profile=RecommendProfile.of(emptyList(),listOf(row),emptySet(),now=1_000)
        assertTrue(profile.completed.contains("9"))
        val partial=BackupProgress(Series("8","未完结","","","全 12 集","玄幻"),"e",5,600_000,600_000,true,1_000)
        assertFalse(RecommendProfile.of(emptyList(),listOf(partial),emptySet(),now=1_000).completed.contains("8"))
    }

    @Test fun resetStopsOldWatchSignalsButKeepsFavorites() {
        val favorite=Series("1","收藏剧","","","","玄幻")
        val old=BackupProgress(Series("2","看过剧","","","","逆袭"),"e",0,60_000,600_000,false,1_000)
        val fresh=BackupProgress(Series("3","新看剧","","","","甜宠"),"e",0,60_000,600_000,false,5_000)
        val profile=RecommendProfile.of(listOf(favorite),listOf(old,fresh),emptySet(),now=6_000,resetAt=4_000)
        assertEquals(4,profile.interest("玄幻"))
        assertEquals(0,profile.interest("逆袭"))
        assertEquals(1,profile.interest("甜宠"))
        assertTrue(profile.isRecent("2",6_000))
    }

    @Test fun explicitWatchedSetIsNeverRecommended() {
        val batch=RecommendSession("s1",RecommendMode.MIXED,RecommendProfile(completed=setOf("1","2"))).append(pool(6,CatalogType.REAL))
        assertFalse(batch.any { it.series.id=="1"||it.series.id=="2" })
    }

    @Test fun recommendModeRoundTripsThroughStoredValue() {
        assertEquals(RecommendMode.MIXED,RecommendMode.fromStored("mixed"))
        assertEquals(RecommendMode.COMIC,RecommendMode.fromStored("comic-drama"))
        assertEquals(RecommendMode.MIXED,RecommendMode.fromStored(null))
        assertEquals(CatalogType.AI_DRAMA,RecommendMode.AI.type)
        assertNull(RecommendMode.MIXED.type)
    }
}
