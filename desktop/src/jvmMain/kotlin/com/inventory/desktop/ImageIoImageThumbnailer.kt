package com.inventory.desktop

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.inventory.storage.ImageThumbnailer
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.ImageOutputStream

/**
 * The desktop [ImageThumbnailer], backed by `javax.imageio` plus the same
 * `metadata-extractor` EXIF reader [DesktopExifEditor] uses: decodes the
 * payload with [ImageIO.read], applies the EXIF orientation (IFD0 tag
 * 0x0112) so the preview is upright, downscales it with a bilinear
 * [java.awt.Graphics2D] draw and re-encodes it as a JPEG at
 * [ImageThumbnailer.THUMBNAIL_QUALITY] using an explicit
 * [ImageWriteParam] (the default ImageIO writer ignores quality).
 *
 * The original bytes are only READ — never written back or re-encoded —
 * and a payload that cannot be decoded yields null so the report falls
 * back to a placeholder cell.
 */
class ImageIoImageThumbnailer : ImageThumbnailer {

    override fun thumbnail(bytes: ByteArray, maxDim: Int): ByteArray? {
        if (maxDim <= 0) return null
        val source = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
            ?: return null
        val upright = applyOrientation(source, orientationOf(bytes))
        val scaled = scaleDown(upright, maxDim)
        return runCatching { writeJpeg(scaled) }.getOrNull()
    }

    /** Orientation of the payload; 1 (normal) when the tag is absent/unreadable. */
    private fun orientationOf(bytes: ByteArray): Int = runCatching {
        val metadata = ImageMetadataReader.readMetadata(ByteArrayInputStream(bytes))
        metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
            ?.getInteger(ExifIFD0Directory.TAG_ORIENTATION)
            ?: 1
    }.getOrDefault(1)

    /**
     * Applies the EXIF orientation. The codes map to these source→target
     * affine transforms (source W×H, target H×W for codes 5..8):
     * 2 flip-X, 3 rotate 180, 4 flip-Y, 5 transpose, 6 rotate 90 CW,
     * 7 transverse, 8 rotate 270 CW.
     */
    private fun applyOrientation(source: BufferedImage, orientation: Int): BufferedImage {
        if (orientation <= 1 || orientation > 8) return source
        val width = source.width.toDouble()
        val height = source.height.toDouble()
        val transform = when (orientation) {
            2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, width, 0.0)
            3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, width, height)
            4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, height)
            5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
            6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, height, 0.0)
            7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, height, width)
            else -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, width)
        }
        val swap = orientation >= 5
        val targetWidth = if (swap) source.height else source.width
        val targetHeight = if (swap) source.width else source.height
        val target = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            graphics.drawImage(source, transform, null)
        } finally {
            graphics.dispose()
        }
        return target
    }

    /** Downscales [source] so its longest side is at most [maxDim], on white. */
    private fun scaleDown(source: BufferedImage, maxDim: Int): BufferedImage {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxDim) return source
        val scale = maxDim.toDouble() / longest
        val width = maxOf(1, (source.width * scale).toInt())
        val height = maxOf(1, (source.height * scale).toInt())
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return target
    }

    private fun writeJpeg(image: BufferedImage): ByteArray {
        val rgb = toRgb(image)
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = ImageThumbnailer.THUMBNAIL_QUALITY / 100f
        }
        val output = ByteArrayOutputStream()
        try {
            val imageOutput: ImageOutputStream = ImageIO.createImageOutputStream(output)
            imageOutput.use {
                writer.output = it
                writer.write(null, IIOImage(rgb, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return output.toByteArray()
    }

    /** A JPEG-writable copy of [source]: itself when already RGB, else flattened onto white. */
    private fun toRgb(source: BufferedImage): BufferedImage {
        if (source.type == BufferedImage.TYPE_INT_RGB) return source
        val target = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        val graphics = target.createGraphics()
        try {
            graphics.drawImage(source, 0, 0, java.awt.Color.WHITE, null)
        } finally {
            graphics.dispose()
        }
        return target
    }
}
