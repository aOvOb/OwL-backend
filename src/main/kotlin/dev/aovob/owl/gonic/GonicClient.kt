package dev.aovob.owl.gonic

import dev.aovob.owl.auth.OwlUser
import dev.aovob.owl.config.OwlProperties
import dev.aovob.owl.youtube.internalRestClient
import org.springframework.stereotype.Component
import org.springframework.web.client.body
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom

/** Error returned by Gonic. Codes 40/41 mean wrong credentials, 70 means not found. */
class GonicException(val code: Int, message: String) : RuntimeException(message)

/** Calls the Subsonic API on Gonic as the logged-in user (token + salt auth, password never sent). */
@Component
class GonicClient(props: OwlProperties) {
	private val base = props.gonicUrl.trimEnd('/')
	private val rest = internalRestClient()
	private val random = SecureRandom()

	fun call(user: OwlUser, method: String, params: Map<String, Any?> = emptyMap()): SubsonicResponse {
		val envelope = rest.get().uri(uri(user, method, params)).retrieve().body<Envelope>()
			?: throw GonicException(0, "empty response from Gonic")
		val response = envelope.response
		if (response.status != "ok") {
			throw GonicException(response.error?.code ?: 0, response.error?.message ?: "Gonic error")
		}
		return response
	}

	private val adminCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean, Long>>()

	/** Whether the user is a Gonic admin (asked once per 5 minutes; any error counts as not admin). */
	fun isAdmin(user: OwlUser): Boolean {
		val now = System.currentTimeMillis()
		adminCache[user.username]?.let { (admin, at) -> if (now - at < 300_000) return admin }
		val admin = runCatching { call(user, "getUser", mapOf("username" to user.username)).user?.adminRole == true }.getOrDefault(false)
		adminCache[user.username] = admin to now
		return admin
	}

	/** Full Gonic URL for binary endpoints (stream, cover art), fetched by MediaController. */
	fun uri(user: OwlUser, method: String, params: Map<String, Any?> = emptyMap()): URI {
		val salt = ByteArray(8).also(random::nextBytes).toHex()
		val token = MessageDigest.getInstance("MD5").digest((user.password + salt).toByteArray()).toHex()
		val builder = UriComponentsBuilder.fromUriString("$base/rest/$method")
			.queryParam("u", user.username)
			.queryParam("t", token)
			.queryParam("s", salt)
			.queryParam("v", "1.16.1")
			.queryParam("c", "owl")
			.queryParam("f", "json")
		params.forEach { (k, v) -> if (v != null) builder.queryParam(k, v) }
		return builder.encode().build().toUri()
	}

	private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
