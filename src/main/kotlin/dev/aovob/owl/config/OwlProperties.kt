package dev.aovob.owl.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Settings under `owl.*` (see application.yml). Secrets come from environment variables.
 *
 * @property sessionKey base64 of 32 random bytes; encrypts the login cookie (OWL_SESSION_KEY).
 */
@ConfigurationProperties("owl")
data class OwlProperties(
	val gonicUrl: String,
	val yubalUrl: String,
	val searchUrl: String,
	val sessionKey: String,
	val cookieSecure: Boolean = true,
	val sessionDays: Long = 30,
)
