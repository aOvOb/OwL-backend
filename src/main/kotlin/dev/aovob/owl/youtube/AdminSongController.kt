package dev.aovob.owl.youtube

import dev.aovob.owl.auth.OwlUser
import dev.aovob.owl.gonic.GonicClient
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

data class SongTags(
	@field:Size(max = 300) val title: String? = null,
	@field:Size(max = 300) val artist: String? = null,
	@field:Size(max = 300) val album: String? = null,
)

/**
 * Song management for admins: list the server's songs, fix their tags, delete them.
 * The files are shared by every account, so every call here checks the Gonic admin role.
 */
@RestController
@RequestMapping("/api/admin/songs")
class AdminSongController(private val charts: ChartsClient, private val gonic: GonicClient) {

	@GetMapping
	fun list(
		@AuthenticationPrincipal user: OwlUser,
		@RequestParam(defaultValue = "") q: String,
		@RequestParam(defaultValue = "0") offset: Int,
	): Map<String, Any?> {
		requireAdmin(user)
		return charts.adminSongs(q, offset.coerceAtLeast(0))
	}

	@PatchMapping("/{id}")
	fun edit(@AuthenticationPrincipal user: OwlUser, @PathVariable id: String, @Valid @RequestBody body: SongTags): Map<String, Any?> {
		requireAdmin(user)
		return charts.adminEdit(songId(id), body.title, body.artist, body.album)
	}

	/** force=true deletes a song even when it is in somebody's playlist. */
	@DeleteMapping("/{id}")
	fun delete(
		@AuthenticationPrincipal user: OwlUser,
		@PathVariable id: String,
		@RequestParam(defaultValue = "false") force: Boolean,
	): Map<String, Any?> {
		requireAdmin(user)
		return charts.adminDelete(songId(id), force)
	}

	private fun requireAdmin(user: OwlUser) {
		if (!gonic.isAdmin(user)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 계정만 곡을 관리할 수 있어요")
	}

	private fun songId(id: String): String {
		if (!SONG_ID.matches(id)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "곡 id 형식이 아닙니다")
		return id
	}

	private companion object {
		val SONG_ID = Regex("^tr-\\d{1,10}$")
	}
}
