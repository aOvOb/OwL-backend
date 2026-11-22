package dev.aovob.owl.library

import dev.aovob.owl.auth.OwlUser
import dev.aovob.owl.config.OwlProperties
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** An album or artist the user put in their library (shown like playlists, per account). */
data class SavedItem(
	val id: String,
	val name: String = "",
	val artist: String? = null,
	val coverArt: String? = null,
	val year: Int? = null,
	val addedAt: Long = 0,
)

data class SavedLibrary(val albums: List<SavedItem> = emptyList(), val artists: List<SavedItem> = emptyList())

data class SaveRequest(
	@field:Size(max = 300) val name: String = "",
	@field:Size(max = 300) val artist: String? = null,
	@field:Size(max = 64) val coverArt: String? = null,
	val year: Int? = null,
)

/**
 * Per-account library: albums and artists the user saved. The server's full album/artist list stays
 * browsable separately; this is "mine".
 */
@RestController
@Validated
@RequestMapping("/api/my/library")
class SavedController(props: OwlProperties, private val mapper: JsonMapper) {
	private val dir: Path = Path.of(props.dataDir, "library")
	private val locks = ConcurrentHashMap<String, ReentrantLock>()

	@GetMapping
	fun get(@AuthenticationPrincipal user: OwlUser): SavedLibrary = read(user)

	@PutMapping("/albums/{id}")
	fun saveAlbum(@AuthenticationPrincipal user: OwlUser, @PathVariable @Pattern(regexp = ID) id: String, @Valid @RequestBody body: SaveRequest) =
		update(user) { it.copy(albums = listOf(item(id, body)) + it.albums.filter { a -> a.id != id }) }

	@DeleteMapping("/albums/{id}")
	fun removeAlbum(@AuthenticationPrincipal user: OwlUser, @PathVariable id: String) =
		update(user) { it.copy(albums = it.albums.filter { a -> a.id != id }) }

	@PutMapping("/artists/{id}")
	fun saveArtist(@AuthenticationPrincipal user: OwlUser, @PathVariable @Pattern(regexp = ID) id: String, @Valid @RequestBody body: SaveRequest) =
		update(user) { it.copy(artists = listOf(item(id, body)) + it.artists.filter { a -> a.id != id }) }

	@DeleteMapping("/artists/{id}")
	fun removeArtist(@AuthenticationPrincipal user: OwlUser, @PathVariable id: String) =
		update(user) { it.copy(artists = it.artists.filter { a -> a.id != id }) }

	private fun item(id: String, b: SaveRequest) = SavedItem(id, b.name.trim(), b.artist, b.coverArt, b.year, System.currentTimeMillis())

	private fun read(user: OwlUser): SavedLibrary {
		val f = file(user)
		return if (Files.exists(f)) mapper.readValue(f.toFile(), SavedLibrary::class.java) else SavedLibrary()
	}

	private fun update(user: OwlUser, change: (SavedLibrary) -> SavedLibrary): SavedLibrary =
		locks.computeIfAbsent(name(user)) { ReentrantLock() }.withLock {
			Files.createDirectories(dir)
			val next = change(read(user))
			val tmp = dir.resolve("${name(user)}.json.tmp")
			mapper.writeValue(tmp.toFile(), next)
			Files.move(tmp, file(user), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
			next
		}

	private fun file(user: OwlUser) = dir.resolve("${name(user)}.json")

	private fun name(user: OwlUser) =
		if (Regex("^[A-Za-z0-9_-]{1,64}$").matches(user.username)) user.username
		else "u-" + user.username.toByteArray().joinToString("") { "%02x".format(it) }

	private companion object {
		const val ID = "^(al|ar)-\\d+$"
	}
}
