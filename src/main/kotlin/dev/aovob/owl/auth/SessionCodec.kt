package dev.aovob.owl.auth

import dev.aovob.owl.config.OwlProperties
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A logged-in user. The Gonic password is kept so the backend can call Gonic on the user's behalf. */
data class OwlUser(val username: String, val password: String) {
	override fun toString() = "OwlUser($username)" // never log the password
}

/**
 * Encrypts the login into the session cookie with AES-256-GCM.
 *
 * Stateless on purpose: any backend instance (blue or green) can read it, so logins survive deploys.
 * The cookie is HttpOnly, so page scripts cannot read it.
 */
@Component
class SessionCodec(props: OwlProperties) {
	private val key: SecretKey
	private val random = SecureRandom()

	init {
		val raw = runCatching { Base64.getDecoder().decode(props.sessionKey) }.getOrDefault(ByteArray(0))
		require(raw.size == 32) { "OWL_SESSION_KEY must be base64 of 32 random bytes" }
		key = SecretKeySpec(raw, "AES")
	}

	fun encode(user: OwlUser, expiresAt: Instant): String {
		val iv = ByteArray(IV_BYTES).also(random::nextBytes)
		val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv)) }
		val plain = "${expiresAt.epochSecond}\n${user.username}\n${user.password}".toByteArray()
		return Base64.getUrlEncoder().withoutPadding().encodeToString(iv + cipher.doFinal(plain))
	}

	/** Returns null for anything tampered with, malformed or expired. */
	fun decode(token: String, now: Instant = Instant.now()): OwlUser? = runCatching {
		val bytes = Base64.getUrlDecoder().decode(token)
		val cipher = Cipher.getInstance(TRANSFORM).apply {
			init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
		}
		val parts = String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)).split("\n", limit = 3)
		if (parts.size != 3 || parts[0].toLong() < now.epochSecond) null else OwlUser(parts[1], parts[2])
	}.getOrNull()

	private companion object {
		const val TRANSFORM = "AES/GCM/NoPadding"
		const val IV_BYTES = 12
		const val TAG_BITS = 128
	}
}
