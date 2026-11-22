package dev.aovob.owl.playlists

import dev.aovob.owl.auth.OwlUser
import dev.aovob.owl.youtube.ChartsClient
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class ItemInput(
	@field:Pattern(regexp = "^[A-Za-z0-9_-]{11}$") val videoId: String? = null,
	@field:Size(max = 64) val songId: String? = null,
	@field:NotBlank @field:Size(max = 300) val title: String = "",
	@field:Size(max = 300) val artist: String = "",
	@field:Size(max = 300) val album: String? = null,
	val duration: Int? = null,
	@field:Size(max = 600) val thumbnail: String? = null,
)

data class CreatePlaylist(@field:NotBlank @field:Size(max = 100) val name: String = "", @field:Size(max = 300) val items: List<@Valid ItemInput> = emptyList())
data class RenamePlaylist(@field:NotBlank @field:Size(max = 100) val name: String = "")
data class AddItems(@field:Size(max = 300) val items: List<@Valid ItemInput> = emptyList())

data class PlaylistSummary(val id: String, val name: String, val count: Int, val cover: Boolean, val updatedAt: Long, val thumbnails: List<String>)
data class ItemView(
	val index: Int, val videoId: String?, val songId: String?, val title: String, val artist: String,
	val album: String?, val duration: Int?, val thumbnail: String?, val downloading: Boolean, val addedAt: Long,
)
data class PlaylistView(val id: String, val name: String, val cover: Boolean, val updatedAt: Long, val items: List<ItemView>)

/**
 * OwL's own playlists. Songs can be added before they are downloaded (adding starts the download);
 * they become playable as soon as the file is in the library.
 */
@RestController
@RequestMapping("/api/my/playlists")
class PlaylistController(
	private val store: PlaylistStore,
	private val charts: ChartsClient,
) {
	@GetMapping
	fun list(@AuthenticationPrincipal user: OwlUser): List<PlaylistSummary> =
		store.read(user.username).sortedByDescending { it.updatedAt }.map { pl ->
			PlaylistSummary(pl.id, pl.name, pl.items.size, pl.cover, pl.updatedAt,
				pl.items.mapNotNull { it.songId ?: it.thumbnail }.distinct().take(4))
		}

	@PostMapping
	fun create(@AuthenticationPrincipal user: OwlUser, @Valid @RequestBody body: CreatePlaylist): PlaylistView {
		val now = System.currentTimeMillis()
		val pl = store.update(user.username) { list ->
			if (list.size >= MAX_PLAYLISTS) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "재생목록은 ${MAX_PLAYLISTS}개까지 만들 수 있어요")
			OwlPlaylist(UUID.randomUUID().toString(), body.name.trim(), now, now, items = merge(emptyList(), body.items))
				.also { list.add(it) }
		}
		startDownloads(pl.items)
		return view(pl)
	}

	@GetMapping("/{id}")
	fun get(@AuthenticationPrincipal user: OwlUser, @PathVariable id: String): PlaylistView = view(find(user, id))

	@PatchMapping("/{id}")
	fun rename(@AuthenticationPrincipal user: OwlUser, @PathVariable id: String, @Valid @RequestBody body: RenamePlaylist): PlaylistView =
		view(modify(user, id) { it.copy(name = body.name.trim()) })

	@DeleteMapping("/{id}")
	fun delete(@AuthenticationPrincipal user: OwlUser, @PathVariable id: String): ResponseEntity<Void> {
		store.update(user.username) { list ->
			if (!list.removeIf { it.id == id }) throw notFound()
		}
		Files.deleteIfExists(store.coverFile(id))
		return ResponseEntity.noContent().build()
	}

	@PostMapping("/{id}/items")
	fun add(@AuthenticationPrincipal user: OwlUser, @PathVariable id: String, @Valid @RequestBody body: AddItems): PlaylistView {
		val before = find(user, id).items.size
		val pl = modify(user, id) { it.copy(items = merge(it.items, body.items)) }
		startDownloads(pl.items.drop(before))
		return view(pl)
	}

	@DeleteMapping("/{id}/items/{index}")
	fun remove(@AuthenticationPrincipal user: OwlUser, @PathVariable id: String, @PathVariable index: Int): PlaylistView =
		view(modify(user, id) { pl ->
			if (index !in pl.items.indices) throw notFound()
			pl.copy(items = pl.items.filterIndexed { i, _ -> i != index })
		})

	@PutMapping("/{id}/cover", consumes = ["image/jpeg", "image/png", "application/octet-stream"])
	fun setCover(@AuthenticationPrincipal user: OwlUser, @PathVariable id: String, request: HttpServletRequest): PlaylistView {
		find(user, id)
		val raw = request.inputStream.readNBytes(CoverImages.MAX_UPLOAD_BYTES + 1)
		val jpeg = CoverImages.normalize(raw)
		Files.createDirectories(store.coverDir)
		val tmp = store.coverDir.resolve("$id.jpg.tmp")
		Files.write(tmp, jpeg)
		Files.move(tmp, store.coverFile(id), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
		return view(modify(user, id) { it.copy(cover = true) })
	}

	@DeleteMapping("/{id}/cover")
	fun removeCover(@AuthenticationPrincipal user: OwlUser, @PathVariable id: String): PlaylistView {
		Files.deleteIfExists(store.coverFile(find(user, id).id))
		return view(modify(user, id) { it.copy(cover = false) })
	}

	@GetMapping("/{id}/cover")
	fun cover(@AuthenticationPrincipal user: OwlUser, @PathVariable id: String): ResponseEntity<ByteArray> {
		val pl = find(user, id)
		val file = store.coverFile(pl.id)
		if (!pl.cover || !Files.exists(file)) throw notFound()
		return ResponseEntity.ok()
			.contentType(MediaType.IMAGE_JPEG)
			.cacheControl(CacheControl.noCache().cachePrivate())
			.body(Files.readAllBytes(file))
	}

	@ExceptionHandler(BadImageException::class)
	fun badImage(e: BadImageException) = ResponseEntity.badRequest().body(mapOf("error" to e.message))

	// ---------- helpers ----------

	private fun find(user: OwlUser, id: String) = store.read(user.username).firstOrNull { it.id == id } ?: throw notFound()

	private fun modify(user: OwlUser, id: String, change: (OwlPlaylist) -> OwlPlaylist): OwlPlaylist =
		store.update(user.username) { list ->
			val i = list.indexOfFirst { it.id == id }
			if (i < 0) throw notFound()
			change(list[i]).copy(updatedAt = System.currentTimeMillis()).also { list[i] = it }
		}

	/** Appends new items, skipping songs already in the playlist. */
	private fun merge(existing: List<PlaylistItem>, add: List<ItemInput>): List<PlaylistItem> {
		val seen = existing.map(::identity).toMutableSet()
		val out = existing.toMutableList()
		val now = System.currentTimeMillis()
		for (i in add) {
			val item = PlaylistItem(i.videoId, i.songId, i.title.trim(), i.artist.trim(), i.album, i.duration, i.thumbnail?.takeIf { it.startsWith("https://") }, now)
			if (seen.add(identity(item)) && out.size < MAX_ITEMS) out.add(item)
		}
		return out
	}

	private fun identity(i: PlaylistItem) = i.videoId ?: i.songId ?: "${i.title}|${i.artist}"

	/** Songs added before being downloaded go into the download queue (playlist songs go before chart/album songs). */
	private fun startDownloads(items: List<PlaylistItem>) {
		val missing = items.filter { it.videoId != null && it.songId == null }
		if (missing.isEmpty()) return
		charts.want(missing.map { mapOf("videoId" to it.videoId, "title" to it.title, "artist" to it.artist, "album" to it.album, "thumbnail" to it.thumbnail) }, "playlist")
	}

	/** Video ids the download queue is still working on (waiting, downloading or retrying later). */
	@Suppress("UNCHECKED_CAST")
	private fun pendingVideoIds(): Set<String> = runCatching {
		val d = charts.downloads()
		listOf("active", "queued", "retrying").flatMap { (d[it] as? List<Map<String, Any?>>).orEmpty() }
			.mapNotNull { it["videoId"] as? String }.toSet()
	}.getOrDefault(emptySet())

	private fun view(pl: OwlPlaylist): PlaylistView {
		val found = charts.lookup(pl.items.map { it.title to it.artist })
		val lookupOk = found.size == pl.items.size
		val active = if (pl.items.any { it.videoId != null }) pendingVideoIds() else emptySet()
		return PlaylistView(pl.id, pl.name, pl.cover, pl.updatedAt, pl.items.mapIndexed { i, it ->
			val songId = if (lookupOk) found[i] ?: it.songId?.takeIf { _ -> it.videoId == null } else it.songId
			ItemView(i, it.videoId, songId, it.title, it.artist, it.album, it.duration, it.thumbnail,
				downloading = songId == null && it.videoId in active, addedAt = it.addedAt)
		})
	}

	private fun notFound() = ResponseStatusException(HttpStatus.NOT_FOUND, "재생목록을 찾을 수 없어요")

	private companion object {
		const val MAX_PLAYLISTS = 200
		const val MAX_ITEMS = 2000
	}
}
