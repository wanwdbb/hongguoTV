// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

/** 仅输出阶段、耗时和媒体有效性，不输出设备、账号、签名或视频 URL。 */
fun main(args: Array<String>) {
    require(args.isNotEmpty() && args.all { it.matches(Regex("[0-9]{1,30}")) }) { "请提供分集 ID" }
    val repo=ContentRepository(playbackSession=PlaybackSession())
    val results=JSONArray()
    try {
        args.forEach { id ->
            val result=JSONObject().put("episode",id)
            val start=System.nanoTime()
            var stage="model"
            try {
                val stream=repo.stream(id)
                result.put("modelMs",(System.nanoTime()-start)/1_000_000).put("quality",stream.quality)
                stage="media"
                RemoteVideo(repo.http,stream).use { video ->
                    video.prepare()
                    val first=video.read(0,minOf(video.total,65536).toInt())
                    val tail=video.read((video.total-65536).coerceAtLeast(0),minOf(video.total,65536).toInt())
                    check(String(first,4,4)=="ftyp") { "媒体头不是 MP4" }
                    result.put("ok",true).put("total",video.total).put("firstBytes",first.size).put("tailBytes",tail.size)
                }
            } catch(error: Exception) {
                result.put("ok",false).put("stage",stage).put("error",error.javaClass.simpleName)
                if(error is PlaybackModelException) result.put("diagnostic",error.diagnostic)
            }
            result.put("elapsedMs",(System.nanoTime()-start)/1_000_000)
            results.put(result)
            println(result.toString())
        }
    } finally {
        repo.http.connectionPool.evictAll(); repo.http.dispatcher.executorService.shutdown()
    }
    val output=java.io.File("outputs/playback-fixed-probe.json")
    output.parentFile.mkdirs(); output.writeText(JSONObject().put("results",results).put("androidPlaybackVerified",false).toString(2))
}
