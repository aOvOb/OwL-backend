package dev.aovob.owl.auth

import dev.aovob.owl.activity.ActivityLog
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

const val SESSION_COOKIE = "owl_session"

/** Reads the session cookie and, when valid, marks the request as authenticated. */
class SessionFilter(private val codec: SessionCodec, private val activity: ActivityLog) : OncePerRequestFilter() {
	override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
		val token = request.cookies?.firstOrNull { it.name == SESSION_COOKIE }?.value
		val user = token?.let(codec::decode)
		if (user != null) {
			SecurityContextHolder.getContext().authentication =
				UsernamePasswordAuthenticationToken.authenticated(user, null, emptyList())
			activity.seen(user.username)
		}
		chain.doFilter(request, response)
	}
}
