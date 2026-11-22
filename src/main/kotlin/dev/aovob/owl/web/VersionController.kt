package dev.aovob.owl.web

import org.springframework.boot.info.BuildProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

data class VersionInfo(val version: String, val build: String)

/** Backend version for the app's about line: semver from the build, commit from the deploy (OWL_BUILD). */
@RestController
class VersionController(build: BuildProperties?) {
	private val info = VersionInfo(
		version = build?.version ?: "dev",
		build = System.getenv("OWL_BUILD")?.take(7) ?: "local",
	)

	@GetMapping("/api/version")
	fun version() = info
}
