package com.hongguotv.core

/** Furthest episode wins; within an episode, furthest millisecond wins. Time only breaks ties. */
object WatchProgressOrder {
    fun compare(episode: Int,position: Long,otherEpisode: Int,otherPosition: Long): Int =
        episode.coerceAtLeast(0).compareTo(otherEpisode.coerceAtLeast(0)).takeIf { it!=0 }
            ?: position.coerceAtLeast(0).compareTo(otherPosition.coerceAtLeast(0))
}
