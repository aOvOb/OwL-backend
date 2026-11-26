package dev.aovob.owl.youtube

import dev.aovob.owl.auth.OwlUser
import dev.aovob.owl.gonic.GonicClient
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** A song to download. title/artist let the queue recognize it in the library once downloaded. */
data class SongToGet(
	@field:Pattern(regexp = "^[A-Za-z0-9_-]{11}$", message = "videoId 형식이 아닙니다") val videoId: String? = null,
	@field:Size(max = 300) val title: String? = null,
	@field:Size(max = 300) val artist: String? = null,
	@field:Size(max = 300) val album: String? = null,
	@field:Size(max = 600) val thumbnail: String? = null,
)

data class DownloadRequest(
	@field:Pattern(regexp = "^[A-Za-z0-9_-]{11}$", message = "videoId 형식이 아닙니다") val videoId: String? = null,
	@field:Pattern(regexp = "^https://(music\\.youtube\\.com|(www\\.)?youtube\\.com|youtu\\.be)/\\S+$", message = "유튜브 주소만 받을 수 있습니다")
	val url: String? = null,
	@field:Size(max = 300) val title: String? = null,
	@field:Size(max = 300) val artist: String? = null,
	@field:Size(max = 300) val album: String? = null,
	@field:Size(max = 600) val thumbnail: String? = null,
)

data class BulkDownloadRequest(@field:Size(max = 500) val items: List<@Valid SongToGet> = emptyList())
data class VideoIds(val videoIds: List<@Pattern(regexp = "^[A-Za-z0-9_-]{11}$") String>? = null)

/**
 * YouTube Music search, charts and lyrics (from the chart service, which knows what is in the library),
 * and downloads. Every download goes through the chart service's queue: one song at a time, rate
 * limited, retried later on failure, and listed as failed (the DLQ) when it cannot be downloaded.
 */
@RestController
@RequestMapping("/api")
class YoutubeController(private val charts: ChartsClient, private val yubal: YubalClient, private val gonic: GonicClient) {

	@GetMapping("/yt/search")
	fun search(@RequestParam q: String, @RequestParam(defaultValue = "0") page: Int): List<Map<String, Any?>> =
		if (q.isBlank()) emptyList() else charts.search(q, page.coerceAtLeast(0)).map(::withDownloading)

	@GetMapping("/charts")
	fun charts(@RequestParam(required = false) key: String?, @RequestParam(required = false) top: Int?): List<Map<String, Any?>> =
		charts.charts(key, top?.coerceIn(1, 100)).map { chart ->
			@Suppress("UNCHECKED_CAST")
			val entries = (chart["entries"] as? List<Map<String, Any?>>).orEmpty()
			chart + ("entries" to entries.map(::withDownloading))
		}

	@PostMapping("/yt/download")
	fun download(@Valid @RequestBody body: DownloadRequest): Map<String, Any?> = when {
		body.url != null -> charts.wantUrl(body.url)
		body.videoId != null -> mapOf("queued" to charts.want(listOf(body.toSong()), "manual"))
		else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "videoId 또는 url이 필요합니다")
	}

	@PostMapping("/yt/download/bulk")
	fun downloadBulk(@Valid @RequestBody body: BulkDownloadRequest): Map<String, Any?> =
		mapOf("queued" to charts.want(body.items.filter { it.videoId != null }.map { it.toMap() }, "manual"))

	@GetMapping("/yt/jobs")
	fun jobs(): Map<String, Any?> = yubal.jobs()

	/** Download queue: paused state, in progress, waiting, retrying later, and failed (DLQ). */
	@GetMapping("/downloads")
	fun downloads(): Map<String, Any?> = charts.downloads()

	/** Retry failed songs (no ids = all of them). */
	@PostMapping("/downloads/retry")
	fun retry(@Valid @RequestBody body: VideoIds): Map<String, Any?> = charts.retry(body.videoIds)

	/**
	 * Remove songs from the queue / failed list. The queue is shared by every account, so removing
	 * more than one song at once (clearing a list) is for admins only.
	 */
	@PostMapping("/downloads/drop")
	fun drop(@AuthenticationPrincipal user: OwlUser, @Valid @RequestBody body: VideoIds): Map<String, Any?> {
		val ids = body.videoIds.orEmpty()
		if (ids.size > 1 && !gonic.isAdmin(user)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 계정만 목록을 한 번에 비울 수 있어요")
		return charts.drop(ids)
	}

	/** Recently downloaded songs (suggestions when adding to a playlist). */
	@GetMapping("/songs/recent")
	fun recent(@RequestParam(defaultValue = "50") size: Int): List<Map<String, Any?>> = charts.recent(size.coerceIn(1, 200))

	/** Synced (Apple Music style) or plain lyrics for a library song. */
	@GetMapping("/lyrics/{songId}")
	fun lyrics(@PathVariable songId: String): Map<String, Any?> {
		if (!Regex("^tr-\\d+$").matches(songId)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 곡 번호예요")
		return charts.lyrics(songId) ?: mapOf("synced" to false, "lines" to emptyList<Any>())
	}

	/** The queue's state for a song ("dl") as the app's simple flag. */
	private fun withDownloading(e: Map<String, Any?>): Map<String, Any?> =
		e + ("downloading" to (e["songId"] == null && e["dl"] in setOf("queued", "sent", "retrying")))

	private fun DownloadRequest.toSong() = mapOf("videoId" to videoId, "title" to title, "artist" to artist, "album" to album, "thumbnail" to thumbnail)
	private fun SongToGet.toMap() = mapOf("videoId" to videoId, "title" to title, "artist" to artist, "album" to album, "thumbnail" to thumbnail)
}
