package dev.aovob.owl.web

import dev.aovob.owl.gonic.GonicException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.client.RestClientException
import org.springframework.web.server.ResponseStatusException

data class ApiError(val error: String)

@RestControllerAdvice
class ErrorHandler {
	private val log = LoggerFactory.getLogger(javaClass)

	@ExceptionHandler(GonicException::class)
	fun gonic(e: GonicException): ResponseEntity<ApiError> {
		val status = when (e.code) {
			40, 41 -> HttpStatus.UNAUTHORIZED // password changed in Gonic: log in again
			70 -> HttpStatus.NOT_FOUND
			else -> HttpStatus.BAD_GATEWAY
		}
		if (status == HttpStatus.BAD_GATEWAY) log.warn("Gonic error {}: {}", e.code, e.message)
		return ResponseEntity.status(status).body(ApiError(e.message ?: "Gonic error"))
	}

	@ExceptionHandler(RestClientException::class)
	fun upstream(e: RestClientException): ResponseEntity<ApiError> {
		log.warn("Upstream call failed: {}", e.message)
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiError("서버 연결에 실패했습니다"))
	}

	@ExceptionHandler(ResponseStatusException::class)
	fun status(e: ResponseStatusException): ResponseEntity<ApiError> =
		ResponseEntity.status(e.statusCode).body(ApiError(e.reason ?: "error"))
}
