package dev.aovob.owl

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class OwlApplication

fun main(args: Array<String>) {
	runApplication<OwlApplication>(*args)
}
