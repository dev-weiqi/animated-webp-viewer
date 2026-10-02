package io.github.devweiqi.animatedwebpviewer

import com.android.tools.adtui.webp.WebpMetadata
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JLabel
import javax.swing.SwingConstants
import kotlin.time.Duration.Companion.milliseconds

private val PREVIEW_LIGHT = JBColor(Color(235, 235, 235), Color(65, 65, 65))
private val PREVIEW_DARK = JBColor(Color(210, 210, 210), Color(50, 50, 50))

fun decodeWebpPreview(bytes: ByteArray): WebpAnimation {
    WebpMetadata.ensureWebpRegistered()
    if (bytes.size > 32 * 1024 * 1024) throw IOException("WebP exceeds the 32 MiB preview limit")
    if (bytes.size < 12 ||
        String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
        String(bytes, 8, 4, Charsets.US_ASCII) != "WEBP"
    ) {
        throw IOException("Invalid WebP file")
    }
    decodeWebpAnimation(bytes, size = Int.MAX_VALUE)?.let { return it }
    ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) throw IOException("WebP decoder is unavailable")
        val reader = readers.next()
        try {
            reader.input = input
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            if (width <= 0 || height <= 0 || width.toLong() * height > 32_000_000) {
                throw IOException("WebP exceeds the 32 million pixel limit")
            }
            val source = reader.read(0)
            return WebpAnimation(listOf(AnimationFrame(source, 100.milliseconds)), 1, width, height)
        } finally {
            reader.dispose()
        }
    }
}

class CheckerboardPreview : JLabel(), javax.swing.Scrollable {
    var zoom: Double? = null
        set(value) {
            field = value
            revalidate()
            repaint()
        }
    var checkerboard = true
        set(value) {
            field = value
            repaint()
        }

    init {
        background = JBColor(Color(245, 245, 245), Color(30, 31, 34))
        minimumSize = Dimension(0, 0)
    }

    fun currentScale(): Double {
        zoom?.let { return it }
        val image = icon ?: return 1.0
        return minOf(1.0, width.toDouble() / image.iconWidth, height.toDouble() / image.iconHeight).coerceAtLeast(0.01)
    }

    override fun getPreferredSize(): Dimension {
        val image = icon ?: return Dimension(0, 0)
        val scale = zoom ?: 1.0
        return Dimension(maxOf(1, (image.iconWidth * scale).toInt()), maxOf(1, (image.iconHeight * scale).toInt()))
    }

    override fun getPreferredScrollableViewportSize(): Dimension = Dimension(512, 512)

    override fun getScrollableTracksViewportWidth(): Boolean = zoom == null || preferredSize.width <= (parent?.width ?: 0)

    override fun getScrollableTracksViewportHeight(): Boolean = zoom == null || preferredSize.height <= (parent?.height ?: 0)

    override fun getScrollableUnitIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)

    override fun getScrollableBlockIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int): Int =
        if (orientation == SwingConstants.HORIZONTAL) visibleRect.width else visibleRect.height

    override fun paintComponent(graphics: Graphics) {
        graphics.color = background
        graphics.fillRect(0, 0, width, height)
        val image = icon as? ImageIcon ?: return
        val scale = currentScale()
        val imageWidth = maxOf(1, (image.iconWidth * scale).toInt())
        val imageHeight = maxOf(1, (image.iconHeight * scale).toInt())
        val x = maxOf(0, (width - imageWidth) / 2)
        val y = maxOf(0, (height - imageHeight) / 2)
        val canvas = graphics.create(x, y, imageWidth, imageHeight) as Graphics2D
        try {
            canvas.color = PREVIEW_LIGHT
            canvas.fillRect(0, 0, imageWidth, imageHeight)
            if (checkerboard) {
                val step = JBUI.scale(8)
                val clip = canvas.clipBounds
                for (row in maxOf(0, clip.y / step)..minOf(imageHeight / step, (clip.y + clip.height) / step)) {
                    for (column in maxOf(0, clip.x / step)..minOf(imageWidth / step, (clip.x + clip.width) / step)) {
                        canvas.color = if ((row + column) % 2 == 0) PREVIEW_LIGHT else PREVIEW_DARK
                        canvas.fillRect(column * step, row * step, step, step)
                    }
                }
            }
            canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION, if (scale >= 1) RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR else RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            canvas.drawImage(image.image, 0, 0, imageWidth, imageHeight, null)
        } finally {
            canvas.dispose()
        }
    }
}

fun scaledResourceImage(source: BufferedImage, size: Int): BufferedImage {
    val scale = (size.toDouble() / maxOf(source.width, source.height)).coerceAtMost(1.0)
    val target = BufferedImage(maxOf(1, (source.width * scale).toInt()), maxOf(1, (source.height * scale).toInt()), BufferedImage.TYPE_INT_ARGB)
    val graphics = target.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.drawImage(source, 0, 0, target.width, target.height, null)
    } finally {
        graphics.dispose()
    }
    return target
}
