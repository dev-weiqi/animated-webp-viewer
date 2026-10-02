package io.github.devweiqi.animatedwebpviewer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingWorker

class WebpEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (!file.isValid || file.isDirectory || !file.extension.equals("webp", ignoreCase = true)) return false
        return try {
            // Only inspect the fixed extended header here; decoding happens off the EDT after opening.
            val header = file.inputStream.use { it.readNBytes(30) }
            header.size == 30 &&
                String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 8, Charsets.US_ASCII) == "WEBPVP8X" &&
                header[16] == 10.toByte() &&
                header[17] == 0.toByte() &&
                header[18] == 0.toByte() &&
                header[19] == 0.toByte() &&
                header[20].toInt() and 2 != 0
        } catch (_: IOException) {
            false
        }
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = WebpEditor(project, file)

    override fun getEditorTypeId(): String = "dev.weiqi.webp.preview"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_OTHER_EDITORS
}

class WebpEditor(project: Project, private val source: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val panel = JPanel(BorderLayout())
    private val content = JPanel(BorderLayout())
    private val reload = JButton("Reload")
    private val toolbar = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEADING))
    private val changes = PropertyChangeSupport(this)
    private var player: WebpPlaybackPanel? = null
    private var worker: SwingWorker<WebpAnimation, Void>? = null
    private var disposed = false
    private var selected = true

    init {
        panel.add(content, BorderLayout.CENTER)
        toolbar.add(reload)
        panel.add(toolbar, BorderLayout.NORTH)
        reload.addActionListener { load() }
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.none { it.file == source }) return
                    ApplicationManager.getApplication().invokeLater {
                        if (!disposed) {
                            if (!source.isValid) changes.firePropertyChange(FileEditor.getPropValid(), true, false)
                            load()
                        }
                    }
                }
            }
        )
        load()
    }

    private fun message(text: String) {
        content.removeAll()
        content.add(JLabel(text, SwingConstants.CENTER).apply { putClientProperty("html.disable", true) }, BorderLayout.CENTER)
        content.revalidate()
        content.repaint()
    }

    private fun load() {
        if (disposed) return
        worker?.cancel(true)
        player?.dispose()
        player = null
        toolbar.isVisible = true
        message("Loading WebP…")
        val task = object : SwingWorker<WebpAnimation, Void>() {
            override fun doInBackground(): WebpAnimation {
                if (!source.isValid) throw IOException("This file no longer exists")
                if (source.length > 32L * 1024 * 1024) throw IOException("WebP exceeds the 32 MiB preview limit")
                val bytes = source.inputStream.use { it.readNBytes(32 * 1024 * 1024 + 1) }
                val animation = decodeWebpPreview(bytes)
                if (isCancelled) {
                    animation.frames.forEach { it.image.flush() }
                    throw CancellationException()
                }
                return animation
            }

            override fun done() {
                if (disposed || worker !== this || isCancelled) return
                try {
                    val preview = WebpPlaybackPanel(get(), fileSize = source.length, reload = ::load)
                    toolbar.isVisible = false
                    player = preview
                    if (!selected) preview.pause()
                    content.removeAll()
                    content.add(preview, BorderLayout.CENTER)
                    content.revalidate()
                    content.repaint()
                } catch (_: CancellationException) {
                    // A newer load or a closed editor owns the UI.
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (exception: ExecutionException) {
                    message(exception.cause?.message ?: "Unable to decode this WebP image")
                }
            }
        }
        worker = task
        task.execute()
    }

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = player ?: reload

    override fun getName(): String = "Animated WebP Viewer"

    override fun getFile(): VirtualFile = source

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = !disposed && source.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = changes.addPropertyChangeListener(listener)

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = changes.removePropertyChangeListener(listener)

    override fun selectNotify() {
        selected = true
    }

    override fun deselectNotify() {
        selected = false
        player?.pause()
    }

    override fun dispose() {
        disposed = true
        worker?.cancel(true)
        player?.dispose()
        player = null
    }
}
