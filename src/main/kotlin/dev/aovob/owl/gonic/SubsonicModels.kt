package dev.aovob.owl.gonic

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

// Subsonic API (as served by Gonic). Only the fields the app uses; everything is nullable
// because servers omit fields freely. These classes are also the backend's API responses.

@JsonIgnoreProperties(ignoreUnknown = true)
data class Envelope(@param:JsonProperty("subsonic-response") val response: SubsonicResponse)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SubsonicResponse(
	val status: String = "",
	val error: SubsonicError? = null,
	val albumList2: AlbumList? = null,
	val album: Album? = null,
	val playlists: Playlists? = null,
	val playlist: Playlist? = null,
	val artists: ArtistsIndex? = null,
	val artist: Artist? = null,
	val searchResult3: SearchResult? = null,
	val randomSongs: SongList? = null,
	val song: Song? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SubsonicError(val code: Int = 0, val message: String = "")

@JsonIgnoreProperties(ignoreUnknown = true)
data class Song(
	val id: String,
	val title: String? = null,
	val artist: String? = null,
	val artistId: String? = null,
	val album: String? = null,
	val albumId: String? = null,
	val coverArt: String? = null,
	val duration: Int? = null,
	val track: Int? = null,
	val year: Int? = null,
	val suffix: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Album(
	val id: String,
	val name: String? = null,
	val artist: String? = null,
	val artistId: String? = null,
	val coverArt: String? = null,
	val songCount: Int? = null,
	val duration: Int? = null,
	val year: Int? = null,
	val song: List<Song>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AlbumList(val album: List<Album> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class Playlist(
	val id: String,
	val name: String? = null,
	val comment: String? = null,
	val owner: String? = null,
	val public: Boolean? = null,
	val songCount: Int? = null,
	val duration: Int? = null,
	val coverArt: String? = null,
	val changed: String? = null,
	val entry: List<Song>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Playlists(val playlist: List<Playlist> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class Artist(
	val id: String,
	val name: String? = null,
	val albumCount: Int? = null,
	val coverArt: String? = null,
	val album: List<Album>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ArtistGroup(val name: String? = null, val artist: List<Artist> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class ArtistsIndex(val index: List<ArtistGroup> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchResult(
	val artist: List<Artist> = emptyList(),
	val album: List<Album> = emptyList(),
	val song: List<Song> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SongList(val song: List<Song> = emptyList())
