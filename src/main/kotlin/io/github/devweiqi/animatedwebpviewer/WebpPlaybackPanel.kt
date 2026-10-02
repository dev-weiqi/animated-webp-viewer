package io.github.devweiqi.animatedwebpviewer

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.util.Locale
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.SwingConstants
import javax.swing.Timer

class WebpPlaybackPanel(private val animation: WebpAnimation, fileSize: Long = 0, reload: () -> Unit = {}) : JPanel(BorderLayout()), Disposable {
    private val preview = CheckerboardPreview()
    private val toggle = JButton("Pause")
    private val position = JLabel()
    private val seek = JSlider(1, animation.frames.size, 1)
    private var updatingSeek = false
    private var frameIndex = 0
    private var completedLoops = 0
    private var finished = false
    private var disposed = false
    private val timer = Timer(0) { advance() }.apply { isRepeats = false }

    init {
        require(animation.frames.isNotEmpty())
        val scroll = JBScrollPane(preview)
        scroll.border = JBUI.Borders.empty()
        add(scroll, BorderLayout.CENTER)
        val controls = JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(6), 0))
        controls.name = "playback"
        toggle.preferredSize = JBUI.size(88, 28)
        controls.add(toggle)
        val metrics = position.getFontMetrics(position.font)
        val counterWidth = (1..animation.frames.size).maxOf { metrics.stringWidth("$it / ${animation.frames.size}") }
        position.preferredSize = Dimension(maxOf(JBUI.scale(72), counterWidth), metrics.height)
        position.horizontalAlignment = SwingConstants.RIGHT
        controls.add(position)
        seek.accessibleContext.accessibleName = "Animation frame"
        seek.isEnabled = animation.frames.size > 1
        seek.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(event: java.awt.event.MouseEvent) {
                if (!disposed) pause()
            }
        })
        seek.addChangeListener {
            if (!updatingSeek && !disposed) {
                pause()
                frameIndex = seek.value - 1
                completedLoops = 0
                finished = false
                showFrame()
            }
        }
        val tools = JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(2), 0))

        fun tool(label: String, icon: javax.swing.Icon? = null, action: () -> Unit) {
            tools.add(
                JButton(if (icon == null) label else "", icon).apply {
                    toolTipText = label
                    accessibleContext.accessibleName = label
                    margin = JBUI.emptyInsets()
                    isContentAreaFilled = false
                    isBorderPainted = false
                    border = JBUI.Borders.empty()
                    val width = if (icon == null) maxOf(JBUI.scale(36), getFontMetrics(font).stringWidth(label) + JBUI.scale(12)) else JBUI.scale(30)
                    preferredSize = Dimension(width, JBUI.scale(28))
                    addActionListener { action() }
                }
            )
        }
        tool("Zoom in", AllIcons.General.ZoomIn) { preview.zoom = (preview.currentScale() * 1.25).coerceAtMost(8.0) }
        tool("Zoom out", AllIcons.General.ZoomOut) { preview.zoom = (preview.currentScale() / 1.25).coerceAtLeast(0.01) }
        tool("1:1") { preview.zoom = 1.0 }
        tool("Toggle transparency", AllIcons.Actions.PreviewDetails) { preview.checkerboard = !preview.checkerboard }
        tool("Reload", AllIcons.Actions.Refresh, reload)
        val bits = animation.frames.first().image.colorModel.pixelSize
        val size = if (fileSize < 1024) "$fileSize B" else String.format(Locale.ROOT, "%.2f kB", fileSize / 1024.0)
        val metadata = JLabel("${animation.width}×${animation.height} WEBP ($bits-bit color) $size")
        metadata.name = "metadata"
        metadata.toolTipText = "Original dimensions, decoded color depth and file size"
        val toolbar = object : JPanel(null) {
            private fun rows(): Int {
                val side = maxOf(tools.preferredSize.width, metadata.preferredSize.width)
                return if (width >= 2 * side + controls.preferredSize.width + JBUI.scale(224 + 32)) {
                    1
                } else if (width >= tools.preferredSize.width + metadata.preferredSize.width + JBUI.scale(24)) {
                    2
                } else {
                    3
                }
            }

            override fun getPreferredSize(): Dimension = Dimension(0, JBUI.scale(36 * rows()))

            override fun doLayout() {
                val gap = JBUI.scale(8)
                val height = JBUI.scale(28)
                val row = JBUI.scale(36)
                tools.setBounds(gap, JBUI.scale(4), tools.preferredSize.width, height)
                val sliderWidth = minOf(JBUI.scale(224), maxOf(0, width - controls.preferredSize.width - 3 * gap))
                val playbackWidth = controls.preferredSize.width + gap + sliderWidth
                controls.setBounds((width - playbackWidth) / 2, if (rows() == 1) JBUI.scale(4) else row + JBUI.scale(4), controls.preferredSize.width, height)
                seek.setBounds(controls.x + controls.width + gap, controls.y + JBUI.scale(2), sliderWidth, JBUI.scale(24))
                metadata.setBounds(maxOf(gap, width - metadata.preferredSize.width - gap), if (rows() == 3) 2 * row + JBUI.scale(4) else JBUI.scale(4), minOf(metadata.preferredSize.width, maxOf(0, width - 2 * gap)), height)
            }
        }
        toolbar.add(tools)
        toolbar.add(controls)
        toolbar.add(seek)
        toolbar.add(metadata)
        toolbar.border = JBUI.Borders.customLineBottom(com.intellij.ui.JBColor.border())
        toolbar.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(event: java.awt.event.ComponentEvent) {
                revalidate()
            }
        })
        add(toolbar, BorderLayout.NORTH)
        toggle.addActionListener {
            if (timer.isRunning) {
                timer.stop()
                toggle.text = "Play"
            } else if (!disposed) {
                if (finished) {
                    frameIndex = 0
                    completedLoops = 0
                    finished = false
                }
                showFrame()
                scheduleFrame()
                toggle.text = "Pause"
            }
        }
        showFrame()
        if (animation.frames.size > 1) {
            scheduleFrame()
        } else {
            toggle.isEnabled = false
            toggle.text = "Static"
        }
    }

    fun pause() {
        timer.stop()
        if (animation.frames.size > 1) toggle.text = "Play"
    }

    private fun showFrame() {
        preview.icon = ImageIcon(animation.frames[frameIndex].image)
        preview.getAccessibleContext().accessibleName = "WebP frame ${frameIndex + 1} of ${animation.frames.size}"
        position.text = "${frameIndex + 1} / ${animation.frames.size}"
        updatingSeek = true
        try {
            seek.value = frameIndex + 1
            seek.toolTipText = "Frame ${frameIndex + 1} of ${animation.frames.size}"
        } finally {
            updatingSeek = false
        }
    }

    private fun scheduleFrame() {
        timer.initialDelay = animation.frames[frameIndex].duration.inWholeMilliseconds.coerceIn(10, Int.MAX_VALUE.toLong()).toInt()
        timer.restart()
    }

    private fun advance() {
        if (disposed) return
        if (frameIndex == animation.frames.lastIndex) {
            completedLoops++
            if (animation.loopCount > 0 && completedLoops >= animation.loopCount) {
                finished = true
                toggle.text = "Play"
                return
            }
            frameIndex = 0
        } else {
            frameIndex++
        }
        showFrame()
        scheduleFrame()
    }

    override fun dispose() {
        disposed = true
        timer.stop()
        toggle.isEnabled = false
        seek.isEnabled = false
        preview.icon = null
        animation.frames.forEach { it.image.flush() }
    }
}
