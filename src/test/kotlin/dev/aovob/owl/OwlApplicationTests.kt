package dev.aovob.owl

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

// Test-only key (32 zero bytes). Real keys live only on the server.
@SpringBootTest(properties = ["owl.session-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="])
class OwlApplicationTests {
	@Test
	fun contextLoads() {
	}
}
