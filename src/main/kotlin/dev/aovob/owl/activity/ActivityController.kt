package dev.aovob.owl.activity

import dev.aovob.owl.auth.OwlUser
import dev.aovob.owl.config.OwlProperties
import dev.aovob.owl.gonic.GonicClient
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate

data class ArtistCount(val name: String, val count: Int)

data class UserActivity(
	val username: String,
	/** Last time the account used the app (any event), epoch ms; null when nothing is recorded. */
	val lastSeen: Long?,
	val lastLogin: Long?,
	/** The last song started, when it was within the last 10 minutes. */
	val nowPlaying: ActivityEvent?,
	val plays: Int,
	val downloads: Int,
	/** Days in the range with any activity. */
	val activeDays: Int,
	/** Plays per day, oldest first, one per day in the range. */
	val daily: List<Int>,
	val topArtists: List<ArtistCount>,
	/** Latest events first (plays, downloads, logins; "active" lines left out). */
	val recent: List<ActivityEvent>,
)

data class ActivitySummary(val days: Int, val from: Long, val users: List<UserActivity>)

/** Admin page: what each account has been doing. Gonic admins only. */
@RestController
@RequestMapping("/api/admin/activity")
class ActivityController(private val activity: ActivityLog, private val gonic: GonicClient, private val props: OwlProperties) {

	@GetMapping
	fun summary(@AuthenticationPrincipal user: OwlUser, @RequestParam(defaultValue = "7") days: Int): ActivitySummary {
		if (!gonic.isAdmin(user)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 계정만 볼 수 있어요")
		val d = days.coerceIn(1, 90)
		val today = LocalDate.now(ActivityLog.ZONE)
		val firstDay = today.minusDays(d - 1L)
		val from = firstDay.atStartOfDay(ActivityLog.ZONE).toInstant().toEpochMilli()
		val events = activity.since(from)
		val now = System.currentTimeMillis()
		val names = (events.map { it.user } + playlistUsers()).toSortedSet()
		val users = names.map { name ->
			val mine = events.filter { it.user == name }
			val plays = mine.filter { it.type == "play" }
			val dayOf = { e: ActivityEvent -> Instant.ofEpochMilli(e.t).atZone(ActivityLog.ZONE).toLocalDate() }
			UserActivity(
				username = name,
				lastSeen = mine.maxOfOrNull { it.t },
				lastLogin = mine.lastOrNull { it.type == "login" }?.t,
				nowPlaying = plays.lastOrNull()?.takeIf { now - it.t < NOW_PLAYING },
				plays = plays.size,
				downloads = mine.filter { it.type == "download" }.sumOf { it.count ?: 1 },
				activeDays = mine.map(dayOf).toSet().size,
				daily = (0 until d).map { i -> firstDay.plusDays(i.toLong()).let { day -> plays.count { dayOf(it) == day } } },
				topArtists = plays.mapNotNull { it.artist?.takeIf(String::isNotBlank) }
					.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(5)
					.map { ArtistCount(it.key, it.value) },
				recent = mine.filter { it.type != "active" }.takeLast(RECENT).reversed(),
			)
		}.sortedByDescending { it.lastSeen ?: 0 }
		return ActivitySummary(d, from, users)
	}

	/** Accounts that have playlists, so an account with no activity yet still shows up. */
	private fun playlistUsers(): List<String> {
		val dir = Path.of(props.dataDir, "playlists")
		if (!Files.isDirectory(dir)) return emptyList()
		return Files.list(dir).use { s -> s.map { it.fileName.toString() }.filter { it.endsWith(".json") }.map { decode(it.removeSuffix(".json")) }.toList() }
	}

	/** PlaylistStore hex-encodes usernames that are not plain ("u-" + hex). */
	private fun decode(name: String) =
		if (name.startsWith("u-") && name.length % 2 == 0) runCatching { String(name.drop(2).chunked(2).map { it.toInt(16).toByte() }.toByteArray()) }.getOrDefault(name)
		else name

	private companion object {
		const val NOW_PLAYING = 10 * 60_000L
		const val RECENT = 50
	}
}
