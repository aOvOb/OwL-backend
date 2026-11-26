package dev.aovob.owl.activity

import dev.aovob.owl.auth.OwlUser
import dev.aovob.owl.config.OwlProperties
import dev.aovob.owl.gonic.GonicClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * One thing a user did. type: login, active (used the app; at most once per 30 minutes), play, download.
 * title/artist are filled for plays and downloads; count is the number of songs asked for in a download.
 */
data class ActivityEvent(
	val t: Long,
	val user: String,
	val type: String,
	val songId: String? = null,
	val title: String? = null,
	val artist: String? = null,
	val count: Int? = null,
)

/**
 * Activity of every account, for the admin page. Appended as JSON lines to one file per month under
 * data-dir/activity (OS file lock: the blue and green containers share the volume). Files older than
 * three months are deleted. Recording never fails a request: errors are only logged.
 */
@Component
class ActivityLog(props: OwlProperties, private val mapper: JsonMapper, private val gonic: GonicClient) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val dir: Path = Path.of(props.dataDir, "activity")
	private val lastActive = ConcurrentHashMap<String, Long>()
	private val lastPlay = ConcurrentHashMap<String, Pair<String, Long>>()
	private val worker = Executors.newSingleThreadExecutor { Thread(it, "activity").apply { isDaemon = true } }
	@Volatile private var prunedOn: LocalDate? = null

	fun login(user: String) = record(ActivityEvent(now(), user, "login"))

	/** Called on every signed-in request; writes at most one "active" line per user per 30 minutes. */
	fun seen(user: String) {
		val t = now()
		val last = lastActive[user]
		if (last != null && t - last < ACTIVE_EVERY) return
		lastActive[user] = t
		worker.execute { record(ActivityEvent(t, user, "active")) }
	}

	/** A song started from the beginning. The same song again within 10 minutes counts once. */
	fun played(user: OwlUser, songId: String) {
		val t = now()
		val last = lastPlay[user.username]
		if (last != null && last.first == songId && t - last.second < SAME_PLAY) return
		lastPlay[user.username] = songId to t
		worker.execute {
			val song = runCatching { gonic.call(user, "getSong", mapOf("id" to songId)).song }.getOrNull()
			record(ActivityEvent(t, user.username, "play", songId, song?.title, song?.artist))
		}
	}

	fun downloaded(user: String, count: Int, title: String?, artist: String?) {
		if (count <= 0) return
		record(ActivityEvent(now(), user, "download", title = title, artist = artist, count = count))
	}

	/** Events since the given time, oldest first. */
	fun since(from: Long): List<ActivityEvent> {
		val start = YearMonth.from(java.time.Instant.ofEpochMilli(from).atZone(ZONE))
		val end = YearMonth.now(ZONE)
		val out = mutableListOf<ActivityEvent>()
		var m = start
		while (!m.isAfter(end)) {
			val f = file(m)
			if (Files.exists(f)) {
				Files.readAllLines(f).forEach { line ->
					if (line.isNotBlank()) runCatching { mapper.readValue(line, ActivityEvent::class.java) }.getOrNull()
						?.takeIf { it.t >= from }?.let(out::add)
				}
			}
			m = m.plusMonths(1)
		}
		return out.sortedBy { it.t }
	}

	private fun record(e: ActivityEvent) {
		try {
			Files.createDirectories(dir)
			val bytes = (mapper.writeValueAsString(e) + "\n").toByteArray()
			FileChannel.open(file(YearMonth.now(ZONE)), StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)
				.use { ch -> ch.lock().use { ch.write(ByteBuffer.wrap(bytes)) } }
			prune()
		} catch (ex: Exception) {
			log.warn("activity not recorded: {}", ex.toString())
		}
	}

	private fun prune() {
		val today = LocalDate.now(ZONE)
		if (prunedOn == today) return
		prunedOn = today
		val keepFrom = YearMonth.now(ZONE).minusMonths(KEEP_MONTHS - 1)
		Files.list(dir).use { files ->
			files.filter { p ->
				val m = FILE.matchEntire(p.fileName.toString()) ?: return@filter false
				YearMonth.parse(m.groupValues[1]).isBefore(keepFrom)
			}.forEach(Files::deleteIfExists)
		}
	}

	private fun file(m: YearMonth) = dir.resolve("activity-$m.jsonl")

	private fun now() = System.currentTimeMillis()

	companion object {
		val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
		const val ACTIVE_EVERY = 30 * 60_000L
		const val SAME_PLAY = 10 * 60_000L
		const val KEEP_MONTHS = 3L
		val FILE = Regex("^activity-(\\d{4}-\\d{2})\\.jsonl$")
	}
}
