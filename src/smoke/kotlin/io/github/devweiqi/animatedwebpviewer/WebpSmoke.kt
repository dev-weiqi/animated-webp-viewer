package io.github.devweiqi.animatedwebpviewer

import com.android.tools.adtui.webp.WebpMetadata
import com.intellij.core.CoreApplicationEnvironment
import com.intellij.mock.MockProject
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.impl.FileEditorProviderManagerImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.BinaryLightVirtualFile
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Base64
import java.util.Comparator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.milliseconds

fun main(args: Array<String>) {
    if (args.size == 2) {
        writePreview(args[0], args[1])
        return
    }
    WebpMetadata.ensureWebpRegistered()
    checkAnimation()
    checkPreviewBounds()
    checkToolbar()
    checkEditor()
}

private fun checkAnimation() {
    val red = Base64.getDecoder().decode("UklGRhwAAABXRUJQVlA4TA8AAAAvAUAAAAcQ/Y/+ByKi/wEA")
    val blue = Base64.getDecoder().decode("UklGRhwAAABXRUJQVlA4TA8AAAAvAUAAAAcQ0f/+ByKi/wEA")
    val green = Base64.getDecoder().decode("UklGRhwAAABXRUJQVlA4TA8AAAAvAUAAEAfQ/4gCBiKi/wEA")
    val bytes = animationFile(
        frame(image = red, x = 0, duration = 40, flags = 2),
        frame(image = blue, x = 2, duration = 70, flags = 3),
        frame(image = green, x = 0, duration = 100, flags = 0)
    )
    val decoded = checkNotNull(decodeWebpAnimation(bytes = bytes, size = 4))
    check(decoded.loopCount == 2 && decoded.frames.size == 3)
    check(decoded.width == 4 && decoded.height == 2)
    check(decoded.frames.map { it.duration } == listOf(40.milliseconds, 70.milliseconds, 100.milliseconds))
    check(decoded.frames[0].image.getRGB(0, 0) == 0xFFFF0000.toInt())
    check(decoded.frames[0].image.getRGB(3, 0) == 0)
    check(decoded.frames[1].image.getRGB(0, 0) == 0xFFFF0000.toInt())
    check(decoded.frames[1].image.getRGB(3, 0) == 0xFF0000FF.toInt())
    val blended = decoded.frames[2].image.getRGB(0, 0)
    check((blended shr 16 and 255) in 126..129 && (blended shr 8 and 255) in 126..129)
    check(decoded.frames[2].image.getRGB(3, 0) == 0) { "Disposed frame must stay transparent even with an opaque ANIM background" }
    val thumbnail = checkNotNull(decodeWebpAnimation(bytes = bytes, size = 64, firstFrameOnly = true))
    check(thumbnail.frames.size == 1 && thumbnail.width == 4 && thumbnail.height == 2)
    check(decodeWebpAnimation(bytes = red, size = 64) == null)
    for (invalid in listOf(bytes.copyOf(newSize = bytes.size - 1), animationFile(frame(image = red, x = 4, duration = 40, flags = 2)))) {
        try {
            decodeWebpAnimation(bytes = invalid, size = 64)
            error("Malformed animation must fail")
        } catch (_: IOException) {
            // Expected validation failure.
        }
    }
    val fixture = Files.createTempDirectory("cmp-webp-check")
    try {
        val path = fixture.resolve("composeResources/files/animation.webp")
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
        check(decodeWebpPreview(Files.readAllBytes(path)).frames.size == 3)
        checkNotNull(readWebpAnimation(path = path, size = 44))
    } finally {
        Files.walk(fixture).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
    checkPlayback(animation = decoded)
    println("WebP frames, duration, loops, blending, disposal, bounds, thumbnail and pause/resume: OK")
}

private fun animationFile(vararg frames: ByteArray): ByteArray {
    val canvas = chunk(tag = "VP8X", payload = byteArrayOf(18, 0, 0, 0, 3, 0, 0, 1, 0, 0))
    // Match exported animations that suggest a white background but contain transparent frames.
    val header = chunk(tag = "ANIM", payload = byteArrayOf(-1, -1, -1, -1, 2, 0))
    val content = "WEBP".toByteArray() + canvas + header + frames.reduce { all, next -> all + next }
    return ByteBuffer.allocate(content.size + 8).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray()).putInt(content.size).put(content).array()
}

private fun frame(image: ByteArray, x: Int, duration: Int, flags: Int): ByteArray = chunk(
    tag = "ANMF",
    payload = byteArrayOf((x / 2).toByte(), 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, duration.toByte(), 0, 0, flags.toByte()) + image.copyOfRange(fromIndex = 12, toIndex = image.size)
)

private fun chunk(tag: String, payload: ByteArray): ByteArray = ByteBuffer.allocate(8 + payload.size + payload.size % 2)
    .order(ByteOrder.LITTLE_ENDIAN).put(tag.toByteArray()).putInt(payload.size).put(payload).array()

private fun checkPlayback(animation: WebpAnimation) {
    lateinit var player: WebpPlaybackPanel
    lateinit var button: JButton
    lateinit var position: JLabel
    val changed = CountDownLatch(1)
    SwingUtilities.invokeAndWait {
        player = WebpPlaybackPanel(animation = animation.copy(loopCount = 0))
        check(components(player).filterIsInstance<JLabel>().single { it.name == "metadata" }.text.startsWith("4×2 WEBP (32-bit color)"))
        val controls = components(player).filterIsInstance<Container>().single { it.name == "playback" }
        button = controls.components.filterIsInstance<JButton>().single()
        position = controls.components.filterIsInstance<JLabel>().single()
        button.doClick(0)
        check(button.text == "Play")
    }
    Thread.sleep(120)
    SwingUtilities.invokeAndWait {
        check(position.text == "1 / 3") { "Paused animation advanced" }
        position.addPropertyChangeListener("text") { changed.countDown() }
        button.doClick(0)
        check(button.text == "Pause")
    }
    check(changed.await(2, TimeUnit.SECONDS)) { "Resumed animation did not advance" }
    SwingUtilities.invokeAndWait {
        player.dispose()
        check(!button.isEnabled)
    }
    lateinit var finite: WebpPlaybackPanel
    val completed = CountDownLatch(1)
    val counterLayouts = mutableSetOf<Pair<Int, Int>>()
    val counterLabels = mutableSetOf<String>()
    SwingUtilities.invokeAndWait {
        finite = WebpPlaybackPanel(animation = animation.copy(frames = List(12) { animation.frames[it % animation.frames.size].copy(duration = 10.milliseconds) }, loopCount = 1))
        val controls = components(finite).filterIsInstance<Container>().single { it.name == "playback" }
        val toggle = controls.components.filterIsInstance<JButton>().single()
        val counter = controls.components.filterIsInstance<JLabel>().single()
        controls.setSize(400, 44)
        counter.addPropertyChangeListener("text") {
            controls.doLayout()
            counterLayouts += counter.width to toggle.x
            counterLabels += counter.text
        }
        toggle.addPropertyChangeListener("text") {
            if (it.newValue == "Play") completed.countDown()
        }
    }
    check(completed.await(2, TimeUnit.SECONDS)) { "Finite animation did not stop" }
    SwingUtilities.invokeAndWait {
        finite.dispose()
        check("9 / 12" in counterLabels && "10 / 12" in counterLabels)
        check(counterLayouts.size == 1) { "Frame counter width and playback button position shifted across digit counts: $counterLayouts" }
    }
}

private fun checkPreviewBounds() {
    SwingUtilities.invokeAndWait {
        for ((width, height) in listOf(200 to 100, 100 to 200)) {
            val source = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    source.setRGB(x, y, if (x < 10 || y < 10 || x >= width - 10 || y >= height - 10) 0xFFFF0000.toInt() else 0xFF0000FF.toInt())
                }
            }
            val preview = CheckerboardPreview()
            preview.icon = ImageIcon(source)
            preview.setSize(100, 100)
            val rendered = BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB)
            val graphics = rendered.createGraphics()
            try {
                preview.paint(graphics)
            } finally {
                graphics.dispose()
            }
            val edgeX = if (width > height) 1 else 26
            val edgeY = if (width > height) 26 else 1
            check(rendered.getRGB(edgeX, edgeY) == 0xFFFF0000.toInt()) { "Preview cropped image edges instead of fitting the viewport" }
            check(rendered.getRGB(50, 50) == 0xFF0000FF.toInt())
            check(rendered.getRGB(0, 0) != 0xFFFF0000.toInt()) { "Preview did not preserve its aspect ratio" }
        }
    }
    println("Landscape and portrait previews fit the viewport without cropping: OK")
}

private fun checkEditor() {
    val lifetime = Disposer.newDisposable()
    val environment = CoreApplicationEnvironment(lifetime)
    val project = MockProject(environment.application.picoContainer, lifetime)
    val provider = WebpEditorProvider()
    val red = Base64.getDecoder().decode("UklGRhwAAABXRUJQVlA4TA8AAAAvAUAAAAcQ/Y/+ByKi/wEA")
    val file = BinaryLightVirtualFile("image.WEBP", red)
    val editors = mutableListOf<FileEditor>()
    try {
        check(!provider.accept(project, file)) { "Static WebP must keep the original editor" }
        val animation = animationFile(frame(red, x = 0, duration = 40, flags = 2), frame(red, x = 2, duration = 40, flags = 2))
        val animatedFile = BinaryLightVirtualFile("animation.WEBP", animation)
        check(provider.accept(project, animatedFile))
        val staticExtended = animation.copyOf().apply { this[20] = (this[20].toInt() and 2.inv()).toByte() }
        check(!provider.accept(project, BinaryLightVirtualFile("static.webp", staticExtended)))
        check(!provider.accept(project, BinaryLightVirtualFile("truncated.webp", animation.copyOf(20))))
        check(!provider.accept(project, BinaryLightVirtualFile("fake.webp", ByteArray(30))))
        check(!provider.accept(project, BinaryLightVirtualFile("animation.png", animation)))
        check(!provider.accept(project, BinaryLightVirtualFile("image.png", red)))
        val original = object : FileEditorProvider {
            override fun accept(project: Project, file: VirtualFile): Boolean = true

            override fun createEditor(project: Project, file: VirtualFile): FileEditor = error("Original editor must not open")

            override fun getEditorTypeId(): String = "original.image"

            override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.NONE
        }
        // Verify the installed platform's real provider filtering, not a copy of its policy logic.
        val manager = FileEditorProviderManagerImpl()
        val filter = manager.javaClass.getDeclaredMethod("postProcessResult", List::class.java).apply { isAccessible = true }
        check(filter.invoke(manager, listOf(original, provider).filter { it.accept(project, animatedFile) }.toMutableList()) == listOf(provider))
        check(filter.invoke(manager, listOf(original, provider).filter { it.accept(project, file) }.toMutableList()) == listOf(original))

        fun open(source: VirtualFile): FileEditor {
            lateinit var editor: FileEditor
            SwingUtilities.invokeAndWait {
                editor = provider.createEditor(project, source)
                editors += editor
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var loaded = false
            while (!loaded && System.nanoTime() < deadline) {
                SwingUtilities.invokeAndWait {
                    loaded = components(editor.component).filterIsInstance<JLabel>().none { it.text == "Loading WebP…" }
                }
                if (!loaded) Thread.sleep(20)
            }
            check(loaded) { "Editor load timed out" }
            return editor
        }

        val static = open(file)
        SwingUtilities.invokeAndWait {
            check(components(static.component).filterIsInstance<JLabel>().any { it.text?.startsWith("2×2 WEBP") == true })
            check(components(static.component).filterIsInstance<JButton>().any { it.text == "Static" })
        }
        val animated = open(BinaryLightVirtualFile("animation.webp", animation))
        SwingUtilities.invokeAndWait {
            check(components(animated.component).filterIsInstance<JButton>().any { it.text == "Pause" })
            animated.deselectNotify()
            check(components(animated.component).filterIsInstance<JButton>().any { it.text == "Play" })
            animated.selectNotify()
            val play = components(animated.component).filterIsInstance<JButton>().single { it.text == "Play" }
            play.doClick(0)
            check(play.text == "Pause")
        }

        fun waitForDimensions(prefix: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var found = false
            while (!found && System.nanoTime() < deadline) {
                SwingUtilities.invokeAndWait {
                    found = components(static.component).filterIsInstance<JLabel>().any { it.name == "metadata" && it.text.startsWith(prefix) }
                }
                if (!found) Thread.sleep(20)
            }
            check(found) { "Reload did not update dimensions to $prefix" }
        }
        file.getOutputStream(null, 1, 1).use { it.write(animation) }
        SwingUtilities.invokeAndWait {
            components(static.component).filterIsInstance<JButton>().single { it.toolTipText == "Reload" }.doClick(0)
        }
        waitForDimensions("4×2")
        file.getOutputStream(null, 2, 2).use { it.write(red) }
        SwingUtilities.invokeAndWait {
            project.messageBus.syncPublisher(com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES).after(
                listOf(com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent(null, file, 1, 2, false))
            )
        }
        waitForDimensions("2×2")
        val broken = open(BinaryLightVirtualFile("broken.webp", byteArrayOf(1, 2, 3)))
        SwingUtilities.invokeAndWait {
            check(components(broken.component).filterIsInstance<JLabel>().any { it.text == "Invalid WebP file" })
            val closing = provider.createEditor(project, file)
            Disposer.dispose(closing)
            check(!closing.isValid)
        }
        println("Editor: static files retain native editor, animated files select plugin, preview loading, pause, tab deselection, manual/automatic reload, malformed files and disposal: OK")
    } finally {
        SwingUtilities.invokeAndWait { editors.forEach(Disposer::dispose) }
        Disposer.dispose(lifetime)
    }
}

private fun components(container: Container): List<java.awt.Component> =
    container.components.flatMap { listOf(it) + if (it is Container) components(it) else emptyList() }

private fun checkToolbar() {
    SwingUtilities.invokeAndWait {
        val image = BufferedImage(813, 813, BufferedImage.TYPE_INT_ARGB)
        var reloads = 0
        val panel = WebpPlaybackPanel(
            WebpAnimation(listOf(AnimationFrame(image, 100.milliseconds)), 1, 813, 813),
            fileSize = 12032,
            reload = { reloads++ }
        )
        try {
            fun layout(container: Container) {
                container.doLayout()
                container.components.filterIsInstance<Container>().forEach(::layout)
            }
            val playback = components(panel).single { it.name == "playback" }
            val metadata = components(panel).filterIsInstance<JLabel>().single { it.name == "metadata" }
            for (width in listOf(1200, 700, 350)) {
                panel.setSize(width, 600)
                repeat(3) { layout(panel) }
                val bounds = SwingUtilities.convertRectangle(playback.parent, playback.bounds, panel)
                check(kotlin.math.abs(bounds.centerX - width / 2.0) <= 1) { "Playback must stay centered at $width" }
                check(bounds.y >= 0 && bounds.maxY <= panel.height)
                check(metadata.width > 0)
            }
            val canvas = components(panel).filterIsInstance<CheckerboardPreview>().single()

            fun click(name: String) = components(panel).filterIsInstance<JButton>().single { it.toolTipText == name }.doClick(0)
            click("1:1")
            check(canvas.zoom == 1.0 && canvas.preferredSize == java.awt.Dimension(813, 813))
            click("Zoom in")
            check(canvas.preferredSize.width > 813)
            click("Zoom out")
            check(canvas.preferredSize.width == 813)
            click("Toggle transparency")
            check(!canvas.checkerboard)
            click("Reload")
            check(reloads == 1)
        } finally {
            panel.dispose()
        }
    }
    println("Toolbar: centered playback at three widths, metadata, full-resolution 1:1, zoom, transparency and reload: OK")
}

private fun writePreview(input: String, output: String) {
    val animation = decodeWebpPreview(Files.readAllBytes(java.nio.file.Path.of(input)))
    SwingUtilities.invokeAndWait {
        javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getCrossPlatformLookAndFeelClassName())
        com.intellij.ui.JBColor.setDark(true)
        com.intellij.ui.IconManager.activate(com.intellij.ui.icons.CoreIconManager())
        com.intellij.openapi.util.IconLoader.activate()
        com.intellij.openapi.util.IconLoader.setUseDarkIcons(true)
        val background = java.awt.Color(43, 45, 48)
        val foreground = java.awt.Color(223, 225, 229)
        for (key in listOf("Panel.background", "Button.background", "ScrollPane.background", "Viewport.background")) {
            javax.swing.UIManager.put(key, background)
        }
        for (key in listOf("Label.foreground", "Button.foreground")) javax.swing.UIManager.put(key, foreground)
        for (key in listOf("Label.font", "Button.font")) javax.swing.UIManager.put(key, java.awt.Font("SansSerif", java.awt.Font.PLAIN, 13))
        val panel = WebpPlaybackPanel(animation, Files.size(java.nio.file.Path.of(input)))
        panel.setSize(1120, 640)

        fun layout(container: Container) {
            container.doLayout()
            container.components.filterIsInstance<Container>().forEach(::layout)
        }
        repeat(4) { layout(panel) }
        val image = BufferedImage(2240, 1280, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.scale(2.0, 2.0)
            panel.printAll(graphics)
            javax.imageio.ImageIO.write(image, "png", java.io.File(output))
        } finally {
            graphics.dispose()
            panel.dispose()
        }
    }
}
