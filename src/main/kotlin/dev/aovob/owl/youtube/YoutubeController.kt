package dev.aovob.owl.youtube

import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

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
 * YouTube Music search and charts (from the chart service, which knows which songs are already in the
 * library) and download requests (Yubal). Entries get `downloading: true` while Yubal is working on them.
 */
@RestController
@RequestMapping("/api")
class YoutubeController(private val charts: ChartsClient, private val yubal: YubalClient) {

	@GetMapping("/yt/search")
	fun search(@RequestParam q: String): List<Map<String, Any?>> =
		if (q.isBlank()) emptyList() else markDownloading(charts.search(q))

	@GetMapping("/charts")
	fun charts(@RequestParam(required = false) key: String?): List<Map<String, Any?>> {
		val active = yubal.activeVideoIds()
		return charts.charts(key).map { chart ->
			@Suppress("UNCHECKED_CAST")
			val entries = (chart["entries"] as? List<Map<String, Any?>>).orEmpty()
			chart + ("entries" to entries.map { it + ("downloading" to (it["songId"] == null && it["videoId"] in active)) })
		}
	}

	@PostMapping("/yt/download")
	fun download(@Valid @RequestBody body: DownloadRequest): Map<String, Any?> {
		val url = body.url ?: body.videoId?.let { "https://music.youtube.com/watch?v=$it" }
			?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "videoId 또는 url이 필요합니다")
		return yubal.submit(url) ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "다운로드 대기열이 가득 찼습니다. 잠시 뒤에 다시 시도하세요.")
	}

	/** "Download all missing". Stops when Yubal's queue is full and reports how many went in. */
	@PostMapping("/yt/download/bulk")
	fun downloadBulk(@Valid @RequestBody body: BulkDownloadRequest): Map<String, Any?> {
		val active = yubal.activeVideoIds()
		var queued = 0
		for (id in body.videoIds.distinct().take(100)) {
			if (id in active) continue
			yubal.submitVideo(id) ?: break
			queued++
		}
		return mapOf("queued" to queued)
	}

	@GetMapping("/yt/jobs")
	fun jobs(): Map<String, Any?> = yubal.jobs()

	private fun markDownloading(items: List<Map<String, Any?>>): List<Map<String, Any?>> {
		val active = yubal.activeVideoIds()
		return items.map { it + ("downloading" to (it["songId"] == null && it["videoId"] in active)) }
	}
}
