package com.hongguotv.core
import org.junit.Assert.*
import org.junit.Test
class PosterWindowTest {
 @Test fun thousandsOfItemsKeepABoundedWindow() {
  for(columns in listOf(3,6)) for(offset in listOf(0,500,100000,400000)) {
   val rows=PosterWindow.range(6000,columns,500,offset,1080)
   assertTrue(rows.count()<=8); assertTrue(rows.first>=0); assertTrue(rows.last<PosterWindow.rows(6000,columns))
  }
 }
 @Test fun emptyAndPartialRows() { assertTrue(PosterWindow.range(0,3,500,0,800).isEmpty()); assertEquals(3,PosterWindow.rows(7,3)); assertEquals(2..2,PosterWindow.range(7,3,500,5000,800)) }
 @Test fun remoteDoesNotWrapRows() { assertEquals(2,PosterWindow.move(2,1,8,3)); assertEquals(3,PosterWindow.move(3,-1,8,3)); assertEquals(-1,PosterWindow.move(1,-3,8,3)) }
 @Test fun incompleteLastRowRemainsReachable() { assertEquals(7,PosterWindow.move(5,3,8,3)); assertEquals(4,PosterWindow.move(7,-3,8,3)) }
 @Test fun searchAndFiltersHaveSeparatePositions() { val s=BrowsePositions(); val a=BrowsePosition("a",25,"a"); s.save("search:one",a); assertNull(s["search:two"]); assertNull(s["category:one"]); assertEquals(a,s["search:one"]) }
 @Test fun refreshClearsOnlyItsOwnPosition() { val s=BrowsePositions(); val a=BrowsePosition("a",0,null); s.save("a",a);s.save("b",a);s.clear("a");assertNull(s["a"]);assertEquals(a,s["b"]) }
 @Test fun savedPositionsAreBounded() { val s=BrowsePositions(2);for(i in 0..3)s.save("$i",BrowsePosition("$i",0,null));assertNull(s["1"]);assertNotNull(s["2"]) }
 @Test fun duplicateRequestsAreSuppressedAndFailureCanRetry() { val g=BrowseLoadGate();val t=g.begin()!!;assertNull(g.begin());assertTrue(g.finish(t));assertNotNull(g.begin()) }
 @Test fun lateResponseCannotWriteIntoClosedPage() { val g=BrowseLoadGate();val t=g.begin()!!;g.close();assertFalse(g.finish(t));assertNull(g.begin()) }
 @Test fun staleTicketDoesNotReleaseNewRequest() { val g=BrowseLoadGate();val first=g.begin()!!;g.finish(first);val next=g.begin()!!;assertFalse(g.finish(first));assertNull(g.begin());assertTrue(g.finish(next)) }
}
