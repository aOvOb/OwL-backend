package dev.aovob.owl.playlists

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

class BadImageException(message: String) : RuntimeException(message)

/**
 * Normalizes an uploaded playlist cover: decode (JPEG/PNG/GIF/BMP only), reject huge or broken images,
 * crop to a centered square, shrink to at most [MAX_SIDE] px and re-encode as JPEG.
 * Re-encoding also drops any metadata (location etc.) and anything hidden in the original file.
 */
object CoverImages {
	const val MAX_UPLOAD_BYTES = 3 * 1024 * 1024
	private const val MAX_SIDE = 1000
	private const val MAX_SOURCE_PIXELS = 40_000_000L

	fun normalize(bytes: ByteArray): ByteArray {
		if (bytes.size > MAX_UPLOAD_BYTES) throw BadImageException("사진은 3MB 이하만 올릴 수 있어요")
		val source = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
			?: throw BadImageException("사진을 읽을 수 없어요")
		val reader = ImageIO.getImageReaders(source).asSequence().firstOrNull()
			?: throw BadImageException("JPEG 또는 PNG 사진만 올릴 수 있어요")
		val image = try {
			reader.input = source
			val w = reader.getWidth(0).toLong()
			val h = reader.getHeight(0).toLong()
			if (w <= 0 || h <= 0 || w * h > MAX_SOURCE_PIXELS) throw BadImageException("사진이 너무 커요")
			reader.read(0)
		} catch (e: BadImageException) {
			throw e
		} catch (e: Exception) {
			throw BadImageException("사진을 읽을 수 없어요")
		} finally {
			reader.dispose()
			source.close()
		}

		val side = minOf(image.width, image.height)
		val x = (image.width - side) / 2
		val y = (image.height - side) / 2
		val target = minOf(side, MAX_SIDE)
		val out = BufferedImage(target, target, BufferedImage.TYPE_INT_RGB)
		out.createGraphics().apply {
			setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
			setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
			drawImage(image, 0, 0, target, target, x, y, x + side, y + side, java.awt.Color.BLACK, null)
			dispose()
		}

		val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
		val buffer = ByteArrayOutputStream()
		ImageIO.createImageOutputStream(buffer).use { output ->
			writer.output = output
			val params = writer.defaultWriteParam.apply {
				compressionMode = ImageWriteParam.MODE_EXPLICIT
				compressionQuality = 0.86f
			}
			writer.write(null, IIOImage(out, null, null), params)
			writer.dispose()
		}
		return buffer.toByteArray()
	}
}
