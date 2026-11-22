package dev.aovob.owl.prefs

import dev.aovob.owl.auth.OwlUser
import dev.aovob.owl.config.OwlProperties
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Per-account app settings. */
data class Prefs(
	@field:Size(max = 20) val chartOrder: List<@Pattern(regexp = "^[a-z_]{1,20}$") String> = emptyList(),
)

@RestController
@RequestMapping("/api/my/prefs")
class PrefsController(props: OwlProperties, private val mapper: JsonMapper) {
	private val dir: Path = Path.of(props.dataDir, "prefs")

	@GetMapping
	fun get(@AuthenticationPrincipal user: OwlUser): Prefs {
		val f = file(user)
		return if (Files.exists(f)) mapper.readValue(f.toFile(), Prefs::class.java) else Prefs()
	}

	@PutMapping
	fun put(@AuthenticationPrincipal user: OwlUser, @Valid @RequestBody body: Prefs): Prefs {
		Files.createDirectories(dir)
		val tmp = dir.resolve("${name(user)}.json.${Thread.currentThread().threadId()}.tmp")
		mapper.writeValue(tmp.toFile(), body)
		Files.move(tmp, file(user), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
		return body
	}

	private fun file(user: OwlUser) = dir.resolve("${name(user)}.json")

	private fun name(user: OwlUser) =
		if (Regex("^[A-Za-z0-9_-]{1,64}$").matches(user.username)) user.username
		else "u-" + user.username.toByteArray().joinToString("") { "%02x".format(it) }
}
