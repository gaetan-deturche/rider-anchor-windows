package dev.anchorwindows

import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.project.Project
import com.intellij.ui.docking.DockContainer
import com.intellij.ui.docking.DockContainerFactory
import com.intellij.ui.docking.DockableContent
import com.intellij.util.ui.ImageUtil
import java.awt.Dimension
import java.awt.Image
import java.awt.image.BufferedImage

/**
 * Routes DockManagerImpl.createNewDockContainerFor() to [AnchorDockContainer]. Deliberately not
 * DockContainerFactory.Persistent — restore is owned by [AnchorWindowsService].
 */
class AnchorDockContainerFactory(private val project: Project) : DockContainerFactory {
    companion object {
        const val TYPE = "anchor-window"
    }

    override fun createContainer(content: DockableContent<*>?): DockContainer =
        AnchorDockContainer(project).also { AnchorWindowsService.getInstance(project).onContainerCreated(it) }
}

/**
 * Dummy content whose only purpose is to route [com.intellij.ui.docking.impl.DockManagerImpl.createNewDockContainerFor]
 * to [AnchorDockContainerFactory] (via [getDockContainerType]) and size the new window.
 * It is not a DockableEditor, so the platform always calls setupToolWindowPane() on the new window.
 */
class AnchorDockableContent : DockableContent<String> {
    override fun getKey(): String = "anchor"

    override fun getPreviewImage(): Image = ImageUtil.createImage(1, 1, BufferedImage.TYPE_INT_ARGB)

    override fun getDockContainerType(): String = AnchorDockContainerFactory.TYPE

    override fun getPreferredSize(): Dimension = Dimension(1000, 700)

    override fun close() {}

    override fun getPresentation(): Presentation = Presentation("Anchor Window")
}
