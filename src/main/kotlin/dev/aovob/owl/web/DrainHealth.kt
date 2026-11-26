package dev.aovob.owl.web

import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * Blue-green deploys: once the new container is up, the deploy script creates /tmp/owl-drain in the old
 * one. Its health then reads DOWN, so Caddy sends every new request (the next song, a reconnect) to the
 * new container while the old one finishes what it is serving, before it is stopped.
 */
@Component("drain")
class DrainHealth : HealthIndicator {
	override fun health(): Health =
		if (Files.exists(DRAIN)) Health.down().withDetail("draining", true).build() else Health.up().build()

	private companion object {
		val DRAIN: Path = Path.of("/tmp/owl-drain")
	}
}
