package dev.aovob.owl.youtube

import dev.aovob.owl.config.OwlProperties
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.util.UriComponentsBuilder

data class DownloadRequest(
	@field:Pattern(regexp = "^[A-Za-z0-9_-]{11}$", message = "videoId 형식이 아닙니다")
	val videoId: String? = null,
	@field:Pattern(regexp = "^https://(music\\.youtube\\.com|(www\\.)?youtube\\.com|youtu\\.be)/\\S+$", message = "유튜브 주소만 받을 수 있습니다")
	val url: String? = null,
)

data class BulkDownloadRequest(
	val videoIds: List<@Pattern(regexp = "^[A-Za-z0-9_-]{11}$") String> = emptyList(),
)

/**
 * YouTube Music search and charts (served by the chart service, which has ytmusicapi and knows which
 * songs are already in the library) and download requests (Yubal).
 * Entries get `downloading: true` while Yubal has an unfinished job for that video.
 */
@RestController
@RequestMapping("/api")
class YoutubeController(props: OwlProperties) {
	private val searchBase = props.searchUrl.trimEnd('/')
	private val yubalBase = props.yubalUrl.trimEnd('/')
	private val rest = RestClient.create()

	@GetMapping("/yt/search")
	fun search(@RequestParam q: String): List<Map<String, Any?>> {
		if (q.isBlank()) return emptyList()
		val uri = UriComponentsBuilder.fromUriString("$searchBase/search").queryParam("q", q.take(200)).encode().build().toUri()
		val items = rest.get().uri(uri).retrieve().body<List<Map<String, Any?>>>().orEmpty()
		return markDownloading(items)
	}

	@GetMapping("/charts")
	fun charts(@RequestParam(required = false) key: String?): List<Map<String, Any?>> {
		val uri = UriComponentsBuilder.fromUriString("$searchBase/charts").apply {
			if (!key.isNullOrBlank()) queryParam("key", key)
		}.encode().build().toUri()
		val charts = rest.get().uri(uri).retrieve().body<List<Map<String, Any?>>>().orEmpty()
		val active = activeVideoIds()
		return charts.map { chart ->
			@Suppress("UNCHECKED_CAST")
			val entries = (chart["entries"] as? List<Map<String, Any?>>).orEmpty()
			chart + ("entries" to entries.map { it + ("downloading" to (it["songId"] == null && it["videoId"] in active)) })
		}
	}

	@PostMapping("/yt/download")
	fun download(@Valid @RequestBody body: DownloadRequest): Map<String, Any?> {
		val url = body.url ?: body.videoId?.let { "https://music.youtube.com/watch?v=$it" }
			?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "videoId 또는 url이 필요합니다")
		return submit(url) ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "다운로드 대기열이 가득 찼습니다. 잠시 뒤에 다시 시도하세요.")
	}

	/** "Download all missing" for a chart. Stops when Yubal's queue is full and reports how many went in. */
	@PostMapping("/yt/download/bulk")
	fun downloadBulk(@Valid @RequestBody body: BulkDownloadRequest): Map<String, Any?> {
		val active = activeVideoIds()
		var queued = 0
		for (id in body.videoIds.distinct().take(100)) {
			if (id in active) continue
			submit("https://music.youtube.com/watch?v=$id") ?: break
			queued++
		}
		return mapOf("queued" to queued)
	}

	@GetMapping("/yt/jobs")
	fun jobs(): Map<String, Any?> = rest.get().uri("$yubalBase/jobs").retrieve().body<Map<String, Any?>>().orEmpty()

	private fun submit(url: String): Map<String, Any?>? = try {
		rest.post().uri("$yubalBase/jobs").contentType(MediaType.APPLICATION_JSON).body(mapOf("url" to url))
			.retrieve().body<Map<String, Any?>>().orEmpty()
	} catch (e: HttpClientErrorException.Conflict) {
		null // queue full
	}

	private fun activeVideoIds(): Set<String> = runCatching {
		@Suppress("UNCHECKED_CAST")
		val jobs = (jobs()["jobs"] as? List<Map<String, Any?>>).orEmpty()
		jobs.filter { it["status"] !in DONE }
			.mapNotNull { (it["url"] as? String)?.let { u -> VIDEO_ID.find(u)?.groupValues?.get(1) } }
			.toSet()
	}.getOrDefault(emptySet())

	private fun markDownloading(items: List<Map<String, Any?>>): List<Map<String, Any?>> {
		val active = activeVideoIds()
		return items.map { it + ("downloading" to (it["songId"] == null && it["videoId"] in active)) }
	}

	private companion object {
		val DONE = setOf("completed", "failed", "cancelled")
		val VIDEO_ID = Regex("[?&]v=([A-Za-z0-9_-]{11})")
	}
}
