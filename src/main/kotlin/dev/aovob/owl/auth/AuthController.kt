package dev.aovob.owl.auth

import dev.aovob.owl.activity.ActivityLog
import dev.aovob.owl.config.OwlProperties
import dev.aovob.owl.gonic.GonicClient
import dev.aovob.owl.gonic.GonicException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class LoginRequest(
	@field:NotBlank @field:Size(max = 64) val username: String,
	@field:NotBlank @field:Size(max = 256) val password: String,
)

/** admin: a Gonic admin, who may also clear whole download lists. */
data class Me(val username: String, val admin: Boolean = false)

/** Login with the Gonic account: the password is checked by Gonic itself (ping). */
@RestController
@RequestMapping("/api")
class AuthController(
	private val gonic: GonicClient,
	private val codec: SessionCodec,
	private val props: OwlProperties,
	private val activity: ActivityLog,
) {
	private val limiter = LoginLimiter()

	@PostMapping("/auth/login")
	fun login(@Valid @RequestBody body: LoginRequest, request: HttpServletRequest): ResponseEntity<Any> {
		val ip = request.remoteAddr
		if (limiter.isLocked(ip)) {
			return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(mapOf("error" to "로그인 시도가 너무 많습니다. 5분 뒤에 다시 시도하세요."))
		}
		val user = OwlUser(body.username.trim(), body.password)
		val ok = try {
			gonic.call(user, "ping")
			true
		} catch (e: GonicException) {
			if (e.code in setOf(40, 41)) false else throw e
		}
		if (!ok) {
			limiter.fail(ip)
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "아이디 또는 비밀번호가 맞지 않습니다."))
		}
		limiter.clear(ip)
		activity.login(user.username)
		val maxAge = Duration.ofDays(props.sessionDays)
		return ResponseEntity.ok()
			.header(HttpHeaders.SET_COOKIE, cookie(codec.encode(user, Instant.now().plus(maxAge)), maxAge).toString())
			.body(Me(user.username, gonic.isAdmin(user)))
	}

	@PostMapping("/auth/logout")
	fun logout(): ResponseEntity<Void> =
		ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build()

	@GetMapping("/me")
	fun me(@AuthenticationPrincipal user: OwlUser) = Me(user.username, gonic.isAdmin(user))

	private fun cookie(value: String, maxAge: Duration) = ResponseCookie.from(SESSION_COOKIE, value)
		.httpOnly(true)
		.secure(props.cookieSecure)
		.sameSite("Lax")
		.path("/")
		.maxAge(maxAge)
		.build()
}

/** 5 wrong passwords from one IP lock that IP out for 5 minutes. */
class LoginLimiter(private val maxFails: Int = 5, private val lock: Duration = Duration.ofMinutes(5)) {
	private data class State(val fails: Int, val lockedUntil: Instant?)

	private val states = ConcurrentHashMap<String, State>()

	fun isLocked(ip: String, now: Instant = Instant.now()) = states[ip]?.lockedUntil?.isAfter(now) == true

	fun fail(ip: String, now: Instant = Instant.now()) {
		states.compute(ip) { _, old ->
			val fails = (old?.takeIf { it.lockedUntil == null }?.fails ?: 0) + 1
			if (fails >= maxFails) State(0, now.plus(lock)) else State(fails, null)
		}
	}

	fun clear(ip: String) {
		states.remove(ip)
	}
}
