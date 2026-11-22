package dev.aovob.owl.playlists

import dev.aovob.owl.config.OwlProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A song in a playlist. It may not be downloaded yet: title/artist (as YouTube Music names them, which is
 * also how downloaded files are tagged) find it in the library once it is.
 */
data class PlaylistItem(
	val videoId: String? = null,
	val songId: String? = null,
	val title: String = "",
	val artist: String = "",
	val album: String? = null,
	val duration: Int? = null,
	val thumbnail: String? = null,
)

data class OwlPlaylist(
	val id: String,
	val name: String,
	val createdAt: Long,
	val updatedAt: Long,
	val cover: Boolean = false,
	val items: List<PlaylistItem> = emptyList(),
)

data class UserPlaylists(val playlists: List<OwlPlaylist> = emptyList())

/**
 * One JSON file per user under data-dir/playlists, cover images under data-dir/covers.
 * Writes are atomic (temp file + rename) and serialized with a lock that also works across the
 * blue and green containers (OS file lock on the shared volume).
 */
@Component
class PlaylistStore(props: OwlProperties, private val mapper: JsonMapper) {
	private val dir: Path = Path.of(props.dataDir, "playlists")
	val coverDir: Path = Path.of(props.dataDir, "covers")
	private val locks = ConcurrentHashMap<String, ReentrantLock>()

	fun read(user: String): List<OwlPlaylist> {
		val file = file(user)
		if (!Files.exists(file)) return emptyList()
		return mapper.readValue(file.toFile(), UserPlaylists::class.java).playlists
	}

	/** Read-modify-write under the user's lock. */
	fun <T> update(user: String, change: (MutableList<OwlPlaylist>) -> T): T {
		Files.createDirectories(dir)
		val key = safeName(user)
		return locks.computeIfAbsent(key) { ReentrantLock() }.withLock {
			FileChannel.open(dir.resolve("$key.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
				ch.lock().use {
					val list = read(user).toMutableList()
					val result = change(list)
					val tmp = dir.resolve("$key.json.tmp")
					mapper.writeValue(tmp.toFile(), UserPlaylists(list))
					Files.move(tmp, file(user), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
					result
				}
			}
		}
	}

	fun coverFile(playlistId: String): Path = coverDir.resolve("$playlistId.jpg")

	private fun file(user: String) = dir.resolve("${safeName(user)}.json")

	/** Usernames go into file names: plain ones as is, anything else hex-encoded. */
	private fun safeName(user: String) =
		if (SAFE.matches(user)) user else "u-" + user.toByteArray().joinToString("") { "%02x".format(it) }

	private companion object {
		val SAFE = Regex("^[A-Za-z0-9_-]{1,64}$")
	}
}
