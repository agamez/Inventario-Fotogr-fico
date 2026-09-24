package com.inventory.desktop

import com.inventory.storage.ImageTranscoder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.ImageOutputStream

/**
 * The desktop [ImageTranscoder], backed by `javax.imageio`: a payload that
 * already starts with the JPEG SOI marker passes through byte-identically,
 * everything else is decoded with [ImageIO.read] and re-encoded as JPEG at
 * the requested quality.
 *
 * The default ImageIO writer ignores a quality parameter, so this uses an
 * explicit `jpeg` [ImageWriteParam] with the requested compression quality
 * (0.9 by default — the same target as the Android codec). Images with an
 * alpha channel are flattened onto white first, since JPEG has no alpha.
 * A payload that cannot be decoded raises [IllegalArgumentException].
 */
class ImageIoImageTranscoder : ImageTranscoder {

    override fun toJpeg(bytes: ByteArray, quality: Int): ByteArray {
        if (ImageTranscoder.isJpeg(bytes)) return bytes
        val source = ImageIO.read(ByteArrayInputStream(bytes))
            ?: throw IllegalArgumentException("Payload is not a decodable image")
        val rgb = toRgb(source)
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality.coerceIn(1, 100) / 100f
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
            graphics.drawImage(source, 0, 0, Color.WHITE, null)
        } finally {
            graphics.dispose()
        }
        return target
    }
}
