package dev.aovob.owl.auth

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginLimiterTest {
	private val t0 = Instant.parse("2026-01-01T00:00:00Z")

	@Test
	fun `locks after five failures and unlocks after five minutes`() {
		val limiter = LoginLimiter()
		repeat(4) { limiter.fail("1.2.3.4", t0) }
		assertFalse(limiter.isLocked("1.2.3.4", t0))
		limiter.fail("1.2.3.4", t0)
		assertTrue(limiter.isLocked("1.2.3.4", t0.plusSeconds(10)))
		assertFalse(limiter.isLocked("5.6.7.8", t0.plusSeconds(10)))
		assertFalse(limiter.isLocked("1.2.3.4", t0.plus(Duration.ofMinutes(5)).plusSeconds(1)))
	}

	@Test
	fun `success clears the count`() {
		val limiter = LoginLimiter()
		repeat(4) { limiter.fail("1.2.3.4", t0) }
		limiter.clear("1.2.3.4")
		limiter.fail("1.2.3.4", t0)
		assertFalse(limiter.isLocked("1.2.3.4", t0))
	}
}
