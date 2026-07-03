package dev.anchorwindows

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener

/**
 * Declaratively registered (plugin.xml projectListeners): the platform instantiates it when the
 * first batch of tool windows registers during project open — independent of ProjectActivity
 * scheduling, which has been observed not to execute the restore in Rider. Both triggers funnel
 * into the idempotent [AnchorWindowsService.scheduleRestoreOnce].
 */
class AnchorWindowsInitListener(private val project: Project) : ToolWindowManagerListener {
    override fun toolWindowsRegistered(ids: MutableList<String>, toolWindowManager: ToolWindowManager) {
        AnchorWindowsService.getInstance(project).scheduleRestoreOnce("toolWindowsRegistered")
    }
}
