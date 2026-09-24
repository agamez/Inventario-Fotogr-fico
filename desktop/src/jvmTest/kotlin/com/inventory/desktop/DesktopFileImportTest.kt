package com.inventory.desktop

import com.inventory.desktop.exif.DesktopExifEditor
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Tests for the desktop inbound-import path (the CLI `--import` twin of the
 * Android share target): [ImageIoImageTranscoder] passes JPEGs through
 * byte-identically and transcodes other formats to JPEG, and
 * [DesktopAppController.importFile] names the stored photo after the source
 * file minus its extension, honours collision suffixes and refuses
 * non-images.
 */
class DesktopFileImportTest {

    private fun controller(): Pair<DesktopAppController, File> {
        val root = Files.createTempDirectory("pi-import").toFile()
        val configFile = File(Files.createTempDirectory("pi-import-config").toFile(), "config.properties")
        return DesktopAppController(root, config = DesktopConfig(configFile)) to root
    }

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        return output.toByteArray()
    }

    @Test
    fun jpegPassesThroughTheTranscoderUntouched() {
        val jpeg = syntheticJpeg()
        assertContentEquals(jpeg, ImageIoImageTranscoder().toJpeg(jpeg))
    }

    @Test
    fun pngIsTranscodedToJpegPreservingDimensions() {
        val converted = ImageIoImageTranscoder().toJpeg(pngBytes(5, 7))
        assertTrue(converted.size >= 3)
        assertTrue(converted[0] == 0xFF.toByte() && converted[1] == 0xD8.toByte())
        val image = ImageIO.read(ByteArrayInputStream(converted))
        assertEquals(5, image.width)
        assertEquals(7, image.height)
    }

    @Test
    fun importFileStoresPngAsJpegNamedAfterTheFileAndSuffixesCollisions() = runTest {
        val (app, root) = controller()
        app.refreshNow()
        app.createFolder("", "Box")
        val source = Files.createTempDirectory("pi-import-src").toFile()
        val png = File(source, "snapshot.png").apply { writeBytes(pngBytes(5, 7)) }

        val first = app.importFile("Box", png)
        assertTrue(first is DesktopAppController.FileImportResult.Imported, "got $first")
        val firstPath = first.relPath
        assertEquals("Box/snapshot.jpg", firstPath)

        val stored = File(root, firstPath)
        assertTrue(stored.isFile)
        val bytes = stored.readBytes()
        assertTrue(bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte(), "stored payload is not JPEG")
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(5, image.width)
        assertEquals(7, image.height)
        assertEquals("snapshot", DesktopExifEditor().read(stored)?.description)
        assertTrue(app.snapshot.value.allPhotos.any { it.relPath == firstPath })

        val second = app.importFile("Box", png)
        assertTrue(second is DesktopAppController.FileImportResult.Imported, "got $second")
        assertEquals("Box/snapshot (2).jpg", second.relPath)
    }

    @Test
    fun importFileRejectsNonImagesAndMissingFiles() = runTest {
        val (app, root) = controller()
        app.refreshNow()
        app.createFolder("", "Box")
        val source = Files.createTempDirectory("pi-import-bad").toFile()
        val bogus = File(source, "notes.txt").apply { writeText("not an image") }

        assertTrue(app.importFile("Box", bogus) is DesktopAppController.FileImportResult.Failed)
        assertTrue(app.importFile("Box", File(source, "missing.png")) is DesktopAppController.FileImportResult.Failed)
        assertTrue(File(root, "Box").list()?.isEmpty() != false)
    }
}
