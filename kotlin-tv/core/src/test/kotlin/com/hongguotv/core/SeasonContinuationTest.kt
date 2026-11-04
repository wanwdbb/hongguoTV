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
}
