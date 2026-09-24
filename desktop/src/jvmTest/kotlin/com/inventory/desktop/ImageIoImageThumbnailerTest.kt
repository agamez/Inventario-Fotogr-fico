package com.inventory.desktop

import com.inventory.storage.ImageThumbnailer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the real desktop [ImageIoImageThumbnailer] against the committed
 * `rich_camera.jpg` fixture, whose EXIF orientation is 6 (rotate 90 CW).
 * ImageIO decodes the stored pixels as 1856×1392 and ignores orientation,
 * so an upright 256px preview must come out portrait (height > width) —
 * proving the EXIF transform was applied — and never exceed [maxDim].
 */
class ImageIoImageThumbnailerTest {

    private val thumbnailer = ImageIoImageThumbnailer()

    private fun fixture(): ByteArray =
        javaClass.getResourceAsStream("/rich_camera.jpg")!!.readBytes()

    @Test
    fun rotatesAndDownscalesTheOrientedFixture() {
        val thumbnail = thumbnailer.thumbnail(fixture(), ImageThumbnailer.THUMBNAIL_MAX_DIM)
        assertNotNull(thumbnail, "oriented fixture did not produce a thumbnail")
        val image = ImageIO.read(ByteArrayInputStream(thumbnail))
        assertNotNull(image, "thumbnail bytes did not decode")
        assertTrue(
            maxOf(image.width, image.height) <= ImageThumbnailer.THUMBNAIL_MAX_DIM,
            "thumbnail too large: ${image.width}x${image.height}",
        )
        assertTrue(
            image.height > image.width,
            "orientation 6 not applied: ${image.width}x${image.height} should be portrait",
        )
    }

    @Test
    fun returnsNullForAnUndecodablePayload() {
        assertNull(thumbnailer.thumbnail("not an image".toByteArray(), ImageThumbnailer.THUMBNAIL_MAX_DIM))
    }

    @Test
    fun downscalesALandscapeImageWithoutChangingOrientation() {
        val source = java.awt.image.BufferedImage(400, 200, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val output = ByteArrayOutputStream()
        ImageIO.write(source, "jpeg", output)
        val thumbnail = thumbnailer.thumbnail(output.toByteArray(), ImageThumbnailer.THUMBNAIL_MAX_DIM)
        assertNotNull(thumbnail)
        val image = ImageIO.read(ByteArrayInputStream(thumbnail))
        assertNotNull(image)
        assertTrue(maxOf(image.width, image.height) <= ImageThumbnailer.THUMBNAIL_MAX_DIM)
        assertTrue(image.width > image.height, "landscape image became ${image.width}x${image.height}")
    }
}
