package dev.aovob.owl.library

import dev.aovob.owl.auth.OwlUser
import dev.aovob.owl.gonic.Album
import dev.aovob.owl.gonic.Artist
import dev.aovob.owl.gonic.GonicClient
import dev.aovob.owl.gonic.Playlist
import dev.aovob.owl.gonic.SearchResult
import dev.aovob.owl.gonic.Song
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class Home(val charts: List<Playlist>, val playlists: List<Playlist>, val recentAlbums: List<Album>)

/** The user's library, read from Gonic. */
@RestController
@RequestMapping("/api")
class LibraryController(private val gonic: GonicClient) {

	@GetMapping("/home")
	fun home(@AuthenticationPrincipal user: OwlUser): Home {
		val playlists = gonic.call(user, "getPlaylists").playlists?.playlist.orEmpty()
		val (charts, mine) = playlists.partition { isChart(it) }
		val recent = gonic.call(user, "getAlbumList2", mapOf("type" to "newest", "size" to 20)).albumList2?.album.orEmpty()
		return Home(charts.sortedBy { CHART_ORDER.indexOfFirst { p -> it.name.orEmpty().startsWith(p) }.let { i -> if (i < 0) 99 else i } }, mine, recent)
	}

	@GetMapping("/playlists")
	fun playlists(@AuthenticationPrincipal user: OwlUser): List<Playlist> =
		gonic.call(user, "getPlaylists").playlists?.playlist.orEmpty()

	@GetMapping("/playlists/{id}")
	fun playlist(@AuthenticationPrincipal user: OwlUser, @PathVariable id: String): Playlist? =
		gonic.call(user, "getPlaylist", mapOf("id" to id)).playlist

	@GetMapping("/albums")
	fun albums(
		@AuthenticationPrincipal user: OwlUser,
		@RequestParam(defaultValue = "newest") type: String,
		@RequestParam(defaultValue = "60") size: Int,
		@RequestParam(defaultValue = "0") offset: Int,
	): List<Album> {
		val safeType = type.takeIf { it in ALBUM_LIST_TYPES } ?: "newest"
		return gonic.call(user, "getAlbumList2", mapOf("type" to safeType, "size" to size.coerceIn(1, 500), "offset" to offset.coerceAtLeast(0)))
			.albumList2?.album.orEmpty()
	}

	@GetMapping("/albums/{id}")
	fun album(@AuthenticationPrincipal user: OwlUser, @PathVariable id: String): Album? =
		gonic.call(user, "getAlbum", mapOf("id" to id)).album

	@GetMapping("/artists")
	fun artists(@AuthenticationPrincipal user: OwlUser): List<Artist> =
		gonic.call(user, "getArtists").artists?.index.orEmpty().flatMap { it.artist }

	@GetMapping("/artists/{id}")
	fun artist(@AuthenticationPrincipal user: OwlUser, @PathVariable id: String): Artist? =
		gonic.call(user, "getArtist", mapOf("id" to id)).artist

	@GetMapping("/search")
	fun search(@AuthenticationPrincipal user: OwlUser, @RequestParam q: String): SearchResult =
		if (q.isBlank()) SearchResult()
		else gonic.call(user, "search3", mapOf("query" to q.take(200), "artistCount" to 10, "albumCount" to 20, "songCount" to 50))
			.searchResult3 ?: SearchResult()

	@GetMapping("/songs/random")
	fun random(@AuthenticationPrincipal user: OwlUser, @RequestParam(defaultValue = "50") size: Int): List<Song> =
		gonic.call(user, "getRandomSongs", mapOf("size" to size.coerceIn(1, 200))).randomSongs?.song.orEmpty()

	private companion object {
		val ALBUM_LIST_TYPES = setOf("newest", "recent", "frequent", "random", "alphabeticalByName", "alphabeticalByArtist", "starred")

		// Playlists written by the chart service, shown in this order on the home screen.
		val CHART_ORDER = listOf("멜론", "YouTube 한국", "Apple Music", "Billboard", "YouTube 미국")

		fun isChart(p: Playlist) = CHART_ORDER.any { p.name.orEmpty().startsWith(it) }
	}
}
