package dev.aovob.owl.youtube

import dev.aovob.owl.config.OwlProperties
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import java.net.http.HttpClient
import java.time.Duration
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import org.springframework.web.util.UriComponentsBuilder

/**
 * RestClient for internal services, pinned to HTTP/1.1. The JDK client otherwise tries an h2c upgrade,
 * and uvicorn (Yubal) then drops the POST body (422 "body: Field required").
 */
fun internalRestClient(): RestClient = RestClient.builder()
	.requestFactory(JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build()).apply {
		setReadTimeout(Duration.ofSeconds(30))
	})
	.build()

/** Yubal (downloader) API. */
@Component
class YubalClient(props: OwlProperties) {
	private val base = props.yubalUrl.trimEnd('/')
	private val rest = internalRestClient()

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
	private val rest = internalRestClient()

	fun search(q: String): List<Map<String, Any?>> {
		val uri = UriComponentsBuilder.fromUriString("$base/search").queryParam("q", q.take(200)).encode().build().toUri()
		return rest.get().uri(uri).retrieve().body<List<Map<String, Any?>>>().orEmpty()
	}

	/** Charts; top=N returns the first N songs of each (home screen) with whole-chart counts. */
	fun charts(key: String?, top: Int? = null): List<Map<String, Any?>> {
		val uri = UriComponentsBuilder.fromUriString("$base/charts").apply {
			if (!key.isNullOrBlank()) queryParam("key", key)
			if (top != null) queryParam("top", top)
		}.encode().build().toUri()
		return rest.get().uri(uri).retrieve().body<List<Map<String, Any?>>>().orEmpty()
	}

	/** Songs most recently added to the library, newest first (Song-shaped: id, title, artist, album, coverArt, duration). */
	fun recent(n: Int): List<Map<String, Any?>> =
		rest.get().uri("$base/recent?n=$n").retrieve().body<List<Map<String, Any?>>>().orEmpty()

	// ---- download queue (rate limited, retried, with a failed list) ----

	/** Queues songs [{videoId, title, artist, album, thumbnail}]. source: manual | playlist | chart | album. */
	fun want(items: List<Map<String, Any?>>, source: String): Int =
		post("/want", mapOf("items" to items, "source" to source))["queued"] as? Int ?: 0

	/** Queues every song of a YouTube Music song/album/playlist link. */
	fun wantUrl(url: String, source: String = "manual"): Map<String, Any?> = post("/want-url", mapOf("url" to url, "source" to source))

	fun downloads(): Map<String, Any?> = rest.get().uri("$base/downloads").retrieve().body<Map<String, Any?>>().orEmpty()

	fun retry(videoIds: List<String>?): Map<String, Any?> = post("/downloads/retry", mapOf("videoIds" to videoIds))

	fun drop(videoIds: List<String>): Map<String, Any?> = post("/downloads/drop", mapOf("videoIds" to videoIds))

	// ---- song management (admins only; the caller checks) ----

	fun adminSongs(q: String, offset: Int): Map<String, Any?> {
		val uri = UriComponentsBuilder.fromUriString("$base/admin/songs").queryParam("q", q.take(200)).queryParam("offset", offset).encode().build().toUri()
		return rest.get().uri(uri).retrieve().body<Map<String, Any?>>().orEmpty()
	}

	fun adminEdit(id: String, title: String?, artist: String?, album: String?): Map<String, Any?> =
		post("/admin/songs/edit", mapOf("id" to id, "title" to title, "artist" to artist, "album" to album))

	fun adminDelete(id: String, force: Boolean): Map<String, Any?> = post("/admin/songs/delete", mapOf("id" to id, "force" to force))

	private fun post(path: String, body: Any): Map<String, Any?> =
		rest.post().uri("$base$path").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body<Map<String, Any?>>().orEmpty()

	/** Lyrics saved next to the audio file: {synced, lines: [{t: seconds or null, text}]}, or null. */
	fun lyrics(songId: String): Map<String, Any?>? {
		val uri = UriComponentsBuilder.fromUriString("$base/lyrics").queryParam("id", songId).encode().build().toUri()
		return rest.get().uri(uri).retrieve().body<Map<String, Any?>>()
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
