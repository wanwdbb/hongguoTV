package com.hongguotv.core
import org.junit.Assert.*
import org.junit.Test
class SeasonContinuationTest {
 private val current=Series("13","谁说没灵根不能修仙的?之无灵证道（第十三季）")
 @Test fun selectsOnlyAdjacentSeasonOfSameTitle(){
  val next=Series("14","谁说没灵根不能修仙的？之无灵证道 第十四季")
  assertEquals(next,SeasonContinuation.select(current,listOf(Series("15",current.title.replace("十三","十五")),next)))
 }
 @Test fun refusesUnrelatedMissingAndAmbiguousTitles(){
  assertNull(SeasonContinuation.select(current,listOf(Series("x","其他剧第十四季"))))
  val next=current.copy(id="14",title=current.title.replace("十三","十四"))
  assertNull(SeasonContinuation.select(current,listOf(next,next.copy(id="other"))))
  assertNull(SeasonContinuation.select(current,listOf(next.copy(preview=true))))
  assertNull(SeasonContinuation.parse("无季号的剧"))
 }
 @Test fun handlesArabicAndChineseSeasons(){
  assertEquals(14,SeasonContinuation.parse("修仙 第１４季")?.season)
  assertEquals(20,SeasonContinuation.parse("修仙 第二十季")?.season)
  assertEquals(101,SeasonContinuation.parse("修仙 第一百零一季")?.season)
 }
 @Test fun actualSeason26ContinuesTo27WithoutSkippingOrUsingPreview(){
  val from=Series("7678692101244062745","谁说没灵根不能修仙的？之无灵证道第26季")
  val next=Series("7679476009350990873","谁说没灵根不能修仙的？之无灵证道第27季")
  val later=next.copy(id="28",title=next.title.replace("27","28"))
  assertEquals(next,SeasonContinuation.select(from,listOf(from,later,next,next)))
  assertNull(SeasonContinuation.select(from,listOf(later,next.copy(preview=true))))
 }
 @Test fun firstSeasonWithoutNumberAndOptionalPrefixContinue(){
  val first=Series("1","修仙传奇")
  val next=Series("2","修仙传奇2季")
  assertEquals(next,SeasonContinuation.select(first,listOf(next)))
 }
 @Test fun relatedSeasonIndexSurvivesDetailWithoutSeasonMetadata(){
  val current=Series("2","修仙传奇",seasonIndex=2)
  val next=Series("3","修仙传奇",seasonIndex=3)
  assertEquals(next,SeasonContinuation.select(current,listOf(next)))
  val verified=SeasonContinuation.verifiedDetail(current,next,Detail(next.copy(seasonIndex=0),listOf("123")))
  assertEquals(3,verified?.series?.seasonIndex)
  assertNull(SeasonContinuation.verifiedDetail(current,next,Detail(next.copy(title="修仙传奇第4季"),listOf("123"))))
 }
 @Test fun rejectsConflictingMetadataUpcomingAndMissingSeason(){
  val current=Series("1","修仙传奇")
  assertNull(SeasonContinuation.select(current,listOf(Series("2","修仙传奇第2季",seasonIndex=3))))
  assertNull(SeasonContinuation.select(current,listOf(Series("2","修仙传奇",seasonIndex=2,preview=true))))
  assertNull(SeasonContinuation.select(current,listOf(Series("2","修仙传奇第3季"))))
 }
 @Test fun actualSeasons39Through42MixNumberPrefixes(){
  val rows=listOf(
   Series("7690809573896113177","谁说没灵根不能修仙的？之无灵证道第39季",seasonIndex=39),
   Series("7694116863835376664","谁说没灵根不能修仙的？之无灵证道40季",seasonIndex=40),
   Series("7694653395486919705","谁说没灵根不能修仙的？之无灵证道41季",seasonIndex=41),
   Series("42","谁说没灵根不能修仙的？之无灵证道第42季",seasonIndex=42))
  for(i in 0..2) {
   assertEquals(rows[i+1],SeasonContinuation.select(rows[i],rows))
   assertNotNull(SeasonContinuation.query(rows[i].title))
   assertNotNull(SeasonContinuation.verifiedDetail(rows[i],rows[i+1],Detail(rows[i+1].copy(seasonIndex=0),listOf("123"))))
  }
 }
}
