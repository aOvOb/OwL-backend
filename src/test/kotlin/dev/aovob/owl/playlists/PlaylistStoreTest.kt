package dev.aovob.owl.playlists

import dev.aovob.owl.config.OwlProperties
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlaylistStoreTest {
	@TempDir
	lateinit var tmp: Path

	private fun store() = PlaylistStore(
		OwlProperties("http://g", "http://y", "http://s", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", dataDir = tmp.toString()),
		JsonMapper.builder().findAndAddModules().build(),
	)

	@Test
	fun `saves and reads back per user`() {
		val s = store()
		s.update("aovob") { it.add(OwlPlaylist("p1", "출근길", 1, 1, items = listOf(PlaylistItem(videoId = "abcdefghijk", title = "곡", artist = "가수")))) }
		s.update("다른사람") { it.add(OwlPlaylist("p2", "x", 1, 1)) }
		assertEquals("출근길", s.read("aovob").single().name)
		assertEquals("가수", s.read("aovob").single().items.single().artist)
		assertEquals("p2", s.read("다른사람").single().id)
		assertTrue(Files.list(tmp.resolve("playlists")).toList().none { it.fileName.toString().contains("다른") }) // hex-encoded name
	}

	@Test
	fun `concurrent updates are not lost`() {
		val s = store()
		val pool = Executors.newFixedThreadPool(8)
		repeat(100) { n -> pool.submit { s.update("aovob") { it.add(OwlPlaylist("p$n", "n$n", 1, 1)) } } }
		pool.shutdown()
		pool.awaitTermination(30, TimeUnit.SECONDS)
		assertEquals(100, s.read("aovob").size)
	}
}

class CoverImagesTest {
	private fun png(w: Int, h: Int): ByteArray = ByteArrayOutputStream().also {
		ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", it)
	}.toByteArray()

	@Test
	fun `crops to a square, shrinks to 1000px and re-encodes as JPEG`() {
		val out = CoverImages.normalize(png(2400, 1600))
		val img = ImageIO.read(out.inputStream())
		assertEquals(1000, img.width)
		assertEquals(1000, img.height)
		assertTrue(out[0] == 0xFF.toByte() && out[1] == 0xD8.toByte(), "JPEG header")
		assertTrue(out.size < 1024 * 1024)
	}

	@Test
	fun `small images are kept small`() {
		val img = ImageIO.read(CoverImages.normalize(png(300, 500)).inputStream())
		assertEquals(300, img.width)
	}

	@Test
	fun `rejects non-images and oversized uploads`() {
		assertFailsWith<BadImageException> { CoverImages.normalize("not an image".toByteArray()) }
		assertFailsWith<BadImageException> { CoverImages.normalize(ByteArray(CoverImages.MAX_UPLOAD_BYTES + 1)) }
	}
}
