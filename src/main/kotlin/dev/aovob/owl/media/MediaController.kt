package dev.aovob.owl.media

import dev.aovob.owl.auth.OwlUser
import dev.aovob.owl.gonic.GonicClient
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Audio and cover images, relayed from Gonic so the browser only talks to this origin
 * (one login cookie, no Gonic credentials in the page). Range requests are passed through for seeking.
 */
@RestController
@RequestMapping("/api")
class MediaController(private val gonic: GonicClient) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

	@GetMapping("/stream/{id}")
	fun stream(@AuthenticationPrincipal user: OwlUser, @PathVariable id: String, request: HttpServletRequest, response: HttpServletResponse) =
		relay(gonic.uri(user, "stream", mapOf("id" to id, "format" to "raw")), request, response, cache = "private, no-transform")

	@GetMapping("/cover/{id}")
	fun cover(
		@AuthenticationPrincipal user: OwlUser,
		@PathVariable id: String,
		@RequestParam(defaultValue = "300") size: Int,
		request: HttpServletRequest,
		response: HttpServletResponse,
	) = relay(gonic.uri(user, "getCoverArt", mapOf("id" to id, "size" to size.coerceIn(32, 1200))), request, response, cache = "private, max-age=604800")

	private fun relay(uri: URI, request: HttpServletRequest, response: HttpServletResponse, cache: String) {
		val upstream = HttpRequest.newBuilder(uri).GET().apply {
			request.getHeader("Range")?.let { header("Range", it) }
		}.build()
		val result = http.send(upstream, HttpResponse.BodyHandlers.ofInputStream())
		result.body().use { body ->
			val type = result.headers().firstValue("Content-Type").orElse("")
			// Gonic reports errors (e.g. missing file) as a JSON body with status 200.
			if (type.startsWith("application/json") || type.startsWith("text/xml")) {
				response.status = 404
				return
			}
			response.status = result.statusCode()
			for (name in PASS_HEADERS) result.headers().firstValue(name).ifPresent { response.setHeader(name, it) }
			response.setHeader("Cache-Control", cache)
			try {
				body.transferTo(response.outputStream)
			} catch (e: IOException) {
				log.debug("client closed media stream: {}", e.message) // seeking or skipping closes streams; not an error
			}
		}
	}

	private companion object {
		val PASS_HEADERS = listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag")
	}
}
