package dev.aovob.owl.activity

import dev.aovob.owl.config.OwlProperties
import dev.aovob.owl.gonic.GonicClient
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActivityLogTest {
	@TempDir
	lateinit var tmp: Path

	private fun log(): ActivityLog {
		val props = OwlProperties("http://g", "http://y", "http://s", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", dataDir = tmp.toString())
		return ActivityLog(props, JsonMapper.builder().findAndAddModules().build(), GonicClient(props))
	}

	@Test
	fun `records events and reads them back in order`() {
		val a = log()
		val start = System.currentTimeMillis() - 1
		a.login("aovob")
		a.downloaded("친구", 3, "RUDE!", "Hearts2Hearts")
		a.downloaded("친구", 0, null, null) // nothing asked for: not recorded
		val events = a.since(start)
		assertEquals(listOf("login", "download"), events.map { it.type })
		assertEquals(3, events[1].count)
		assertEquals("친구", events[1].user)
		assertTrue(Files.list(tmp.resolve("activity")).toList().single().fileName.toString().matches(Regex("""activity-\d{4}-\d{2}\.jsonl""")))
	}

	@Test
	fun `active is written at most once per 30 minutes`() {
		val a = log()
		val start = System.currentTimeMillis() - 1
		repeat(5) { a.seen("aovob") }
		a.seen("친구")
		Thread.sleep(500) // written in the background
		assertEquals(listOf("aovob", "친구"), a.since(start).filter { it.type == "active" }.map { it.user }.sorted())
	}

	@Test
	fun `old events are left out`() {
		val a = log()
		a.login("aovob")
		assertTrue(a.since(System.currentTimeMillis() + 60_000).isEmpty())
	}
}
