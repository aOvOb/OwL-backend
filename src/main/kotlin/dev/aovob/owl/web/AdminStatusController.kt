package dev.aovob.owl.web

import dev.aovob.owl.auth.OwlUser
import dev.aovob.owl.config.OwlProperties
import dev.aovob.owl.gonic.GonicClient
import dev.aovob.owl.youtube.ChartsClient
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * Admin page: the server's state. The host writes data-dir/status.json once a minute (ops/owl-status:
 * containers, load, memory, disk, backup, cost guard); the download queue comes from the chart service.
 * Gonic admins only.
 */
@RestController
class AdminStatusController(
	private val gonic: GonicClient,
	private val charts: ChartsClient,
	private val mapper: JsonMapper,
	private val version: VersionController,
	props: OwlProperties,
) {
	private val file: Path = Path.of(props.dataDir, "status.json")
	private val history: Path = Path.of(props.dataDir, "status-history.jsonl")

	@GetMapping("/api/admin/status")
	fun status(@AuthenticationPrincipal user: OwlUser): Map<String, Any?> {
		if (!gonic.isAdmin(user)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 계정만 볼 수 있어요")
		val host = runCatching { if (Files.exists(file)) mapper.readValue(file.toFile(), Map::class.java) else null }.getOrNull()
		val dl = runCatching { charts.downloads() }.getOrNull()
		return mapOf(
			"host" to host,
			"downloads" to dl?.let { d -> mapOf("counts" to d["counts"], "pausedUntil" to d["pausedUntil"], "pausedManually" to d["pausedManually"], "tunnel" to d["tunnel"]) },
			"backend" to version.version(),
		)
	}

	/**
	 * CPU (busy %) and memory (bytes used) over the last `hours` (at most 7 days), one line a minute on the
	 * server, averaged into at most 240 points. A gap (server off) stays a gap: no point is made up for it.
	 */
	@GetMapping("/api/admin/status/history")
	fun history(@AuthenticationPrincipal user: OwlUser, @RequestParam(defaultValue = "24") hours: Int): Map<String, Any?> {
		if (!gonic.isAdmin(user)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 계정만 볼 수 있어요")
		val h = hours.coerceIn(1, 24 * 7)
		val now = System.currentTimeMillis() / 1000
		val from = now - h * 3600L
		val rows = if (!Files.exists(history)) emptyList() else Files.readAllLines(history).mapNotNull { line ->
			runCatching { mapper.readValue(line, HistoryRow::class.java) }.getOrNull()?.takeIf { it.t >= from }
		}
		val step = maxOf(60L, h * 3600L / MAX_POINTS)
		val points = rows.groupBy { it.t / step }.toSortedMap().map { (bucket, rs) ->
			val cpu = rs.mapNotNull { it.cpu }
			mapOf(
				"t" to bucket * step,
				"cpu" to cpu.takeIf { it.isNotEmpty() }?.average()?.let { Math.round(it * 10) / 10.0 },
				"cpuMax" to cpu.maxOrNull(),
				"mem" to rs.mapNotNull { it.mem }.takeIf { it.isNotEmpty() }?.average()?.toLong(),
			)
		}
		return mapOf("from" to from, "to" to now, "step" to step, "points" to points)
	}

	data class HistoryRow(val t: Long = 0, val cpu: Double? = null, val mem: Long? = null, val load: Double? = null)

	private companion object {
		const val MAX_POINTS = 240
	}
}
