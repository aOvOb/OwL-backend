package dev.aovob.owl.auth

import dev.aovob.owl.config.OwlProperties
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionCodecTest {
	private fun props(key: String) = OwlProperties("http://g", "http://y", "http://s", key)
	private val codec = SessionCodec(props("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))
	private val user = OwlUser("aovob", "p@ss\nword with newline")

	@Test
	fun `round trip keeps user and password`() {
		val token = codec.encode(user, Instant.now().plus(Duration.ofDays(1)))
		assertEquals(user, codec.decode(token))
	}

	@Test
	fun `expired token is rejected`() {
		val token = codec.encode(user, Instant.now().minusSeconds(1))
		assertNull(codec.decode(token))
	}

	@Test
	fun `tampered token is rejected`() {
		val token = codec.encode(user, Instant.now().plus(Duration.ofDays(1)))
		val flipped = token.dropLast(2) + (if (token[token.length - 2] == 'A') "B" else "A") + token.last()
		assertNull(codec.decode(flipped))
		assertNull(codec.decode("not-a-token"))
	}

	@Test
	fun `token from another key is rejected`() {
		val other = SessionCodec(props("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="))
		assertNull(other.decode(codec.encode(user, Instant.now().plus(Duration.ofDays(1)))))
	}

	@Test
	fun `missing or short key fails at startup`() {
		assertFailsWith<IllegalArgumentException> { SessionCodec(props("")) }
		assertFailsWith<IllegalArgumentException> { SessionCodec(props("c2hvcnQ=")) }
	}

	@Test
	fun `toString never shows the password`() {
		assertTrue("p@ss" !in user.toString())
	}
}
