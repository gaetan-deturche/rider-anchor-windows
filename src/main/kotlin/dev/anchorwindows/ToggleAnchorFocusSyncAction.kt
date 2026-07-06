package dev.anchorwindows

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware

/**
 * When enabled, focusing the main frame raises the anchor windows (and vice versa) without
 * activating them, so the window group stays together in z-order across alt-tab/clicks;
 * minimizing/restoring any window of the group applies to all of them as well.
 * Application-level setting (PropertiesComponent).
 */
class ToggleAnchorFocusSyncAction : ToggleAction(), DumbAware {
    override fun isSelected(e: AnActionEvent): Boolean = AnchorWindowsService.isFocusSyncEnabled()

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        PropertiesComponent.getInstance().setValue(AnchorWindowsService.FOCUS_SYNC_PROPERTY, state, false)
        if (state) {
            e.project?.let { AnchorWindowsService.getInstance(it).installFocusSync() }
        }
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
