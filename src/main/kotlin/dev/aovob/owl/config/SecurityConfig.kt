package dev.aovob.owl.config

import dev.aovob.owl.auth.SessionCodec
import dev.aovob.owl.auth.SessionFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * Stateless security: the encrypted `owl_session` cookie is the only credential.
 *
 * CSRF tokens are off because the cookie is SameSite=Lax and every state-changing call is a JSON POST,
 * which browsers will not send cross-site without a CORS preflight (and no CORS is allowed).
 */
@Configuration
class SecurityConfig {
	@Bean
	fun securityFilterChain(http: HttpSecurity, codec: SessionCodec): SecurityFilterChain {
		http
			.csrf { it.disable() }
			.httpBasic { it.disable() }
			.formLogin { it.disable() }
			.logout { it.disable() }
			.requestCache { it.disable() }
			.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
			.authorizeHttpRequests {
				it.requestMatchers("/api/auth/login", "/api/auth/logout", "/actuator/health/**", "/actuator/health").permitAll()
				it.anyRequest().authenticated()
			}
			.exceptionHandling { it.authenticationEntryPoint { _, response, _ -> response.status = 401 } }
			.headers { headers ->
				headers.contentTypeOptions { }
				headers.frameOptions { it.deny() }
			}
			.addFilterBefore(SessionFilter(codec), UsernamePasswordAuthenticationFilter::class.java)
		return http.build()
	}

	/** No local users: stops Spring Boot from generating a default password. */
	@Bean
	fun userDetailsService(): UserDetailsService = InMemoryUserDetailsManager()
}
