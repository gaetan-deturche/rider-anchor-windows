package dev.anchorwindows

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ThreeComponentsSplitter
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.toolWindow.ToolWindowPane
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.awt.RelativeRectangle
import com.intellij.ui.docking.DockContainer
import com.intellij.ui.docking.DockableContent
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.HierarchyEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Editor-less dock container: a plain placeholder for the frame's center (document) area.
 *
 * All the real content — the tool windows — lives in the [ToolWindowPane] that the platform's
 * DockWindow wraps around this container ([DockWindow.setupToolWindowPane]). Because this
 * container holds no EditorsSplitters, navigation/`openFile` can never target this frame:
 * files always open in the main frame, even when focus is here.
 *
 * Deliberately NOT [DockContainer.Persistent]: anchor windows are saved and restored by
 * [AnchorWindowsService], not by the platform (see [AnchorWindowsState]).
 */
class AnchorDockContainer(private val project: Project) : DockContainer, Disposable {
    private val panel = JPanel(BorderLayout()).apply {
        val hint = JLabel("Drag tool windows to the edges of this window", SwingConstants.CENTER)
        hint.foreground = JBColor.GRAY
        hint.border = JBUI.Borders.empty(8)
        add(hint, BorderLayout.CENTER)
    }

    init {
        // Docked tool windows are the first/last components of the ThreeComponentsSplitters this
        // panel sits in as the inner component; an invisible inner component gets zero size and
        // the tool windows absorb its space (ThreeComponentsSplitter.doLayout). So: placeholder
        // visible only while no tool window is docked in this frame.
        project.messageBus.connect(this).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun stateChanged(toolWindowManager: ToolWindowManager) = updatePlaceholderVisibility()
        })
        panel.addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.PARENT_CHANGED.toLong() != 0L) {
                updatePlaceholderVisibility()
            }
        }
    }

    /** Pane id of the ToolWindowPane wrapping this container; null until the window is built. */
    internal fun paneId(): String? =
        generateSequence(panel.parent) { it.parent }.filterIsInstance<ToolWindowPane>().firstOrNull()?.paneId

    private fun updatePlaceholderVisibility() {
        val splitters = generateSequence(panel.parent) { it.parent }
            .filterIsInstance<ThreeComponentsSplitter>()
            .toList()
        if (splitters.isEmpty()) return
        val hasToolWindows = splitters.any {
            it.firstComponent?.isVisible == true || it.lastComponent?.isVisible == true
        }
        if (panel.isVisible != !hasToolWindows) {
            panel.isVisible = !hasToolWindows
            for (splitter in splitters) {
                splitter.revalidate()
                splitter.repaint()
            }
        }
    }

    override fun getAcceptArea(): RelativeRectangle = RelativeRectangle(panel)

    // Never accept editor tabs (or anything else) dropped into the center area.
    override fun getContentResponse(content: DockableContent<*>, point: RelativePoint?): DockContainer.ContentResponse =
        DockContainer.ContentResponse.DENY

    override fun getContainerComponent(): JComponent = panel

    override fun add(content: DockableContent<*>, dropTarget: RelativePoint?) {
        // Unreachable: getContentResponse() always denies. Called once with our own
        // AnchorDockableContent right after window creation — nothing to do.
    }

    // false so the platform's closeIfEmpty() never disposes the window when the last
    // tool window is dragged away — an empty anchor stays open until the user closes it.
    override fun isEmpty(): Boolean = false

    override fun isDisposeWhenEmpty(): Boolean = false

    // Called by the platform (DockWindow/DockManagerImpl) when the window closes; also releases
    // the message bus connection registered on this container.
    override fun dispose() {
        if (!project.isDisposed) {
            project.serviceIfCreated<AnchorWindowsService>()?.onContainerDisposed(this)
        }
    }
}
