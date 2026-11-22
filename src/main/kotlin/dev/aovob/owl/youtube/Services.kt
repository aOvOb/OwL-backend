package dev.aovob.owl.youtube

import dev.aovob.owl.config.OwlProperties
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import org.springframework.web.util.UriComponentsBuilder

/** Yubal (downloader) API. */
@Component
class YubalClient(props: OwlProperties) {
	private val base = props.yubalUrl.trimEnd('/')
	private val rest = RestClient.create()

	/** Queues a download. Returns null when Yubal's queue is full. */
	fun submit(url: String): Map<String, Any?>? = try {
		rest.post().uri("$base/jobs").contentType(MediaType.APPLICATION_JSON).body(mapOf("url" to url))
			.retrieve().body<Map<String, Any?>>().orEmpty()
	} catch (e: HttpClientErrorException.Conflict) {
		null
	}

	fun submitVideo(videoId: String) = submit("https://music.youtube.com/watch?v=$videoId")

	fun jobs(): Map<String, Any?> = rest.get().uri("$base/jobs").retrieve().body<Map<String, Any?>>().orEmpty()

	/** Video ids with an unfinished job. */
	fun activeVideoIds(): Set<String> = runCatching {
		@Suppress("UNCHECKED_CAST")
		val jobs = (jobs()["jobs"] as? List<Map<String, Any?>>).orEmpty()
		jobs.filter { it["status"] !in DONE }
			.mapNotNull { (it["url"] as? String)?.let { u -> VIDEO_ID.find(u)?.groupValues?.get(1) } }
			.toSet()
	}.getOrDefault(emptySet())

	private companion object {
		val DONE = setOf("completed", "failed", "cancelled")
		val VIDEO_ID = Regex("[?&]v=([A-Za-z0-9_-]{11})")
	}
}

/** The chart service: YouTube Music search, charts, and "is this song in the library?" lookups. */
@Component
class ChartsClient(props: OwlProperties) {
	private val base = props.searchUrl.trimEnd('/')
	private val rest = RestClient.create()

	fun search(q: String): List<Map<String, Any?>> {
		val uri = UriComponentsBuilder.fromUriString("$base/search").queryParam("q", q.take(200)).encode().build().toUri()
		return rest.get().uri(uri).retrieve().body<List<Map<String, Any?>>>().orEmpty()
	}

	fun charts(key: String?): List<Map<String, Any?>> {
		val uri = UriComponentsBuilder.fromUriString("$base/charts").apply {
			if (!key.isNullOrBlank()) queryParam("key", key)
		}.encode().build().toUri()
		return rest.get().uri(uri).retrieve().body<List<Map<String, Any?>>>().orEmpty()
	}

	/** Library song id for each (title, artist), or null when not downloaded yet. */
	fun lookup(items: List<Pair<String, String>>): List<String?> {
		if (items.isEmpty()) return emptyList()
		val body = items.map { (t, a) -> mapOf("title" to t, "artist" to a) }
		return runCatching {
			rest.post().uri("$base/lookup").contentType(MediaType.APPLICATION_JSON).body(body)
				.retrieve().body<List<String?>>().orEmpty()
		}.getOrDefault(emptyList())
	}
}
