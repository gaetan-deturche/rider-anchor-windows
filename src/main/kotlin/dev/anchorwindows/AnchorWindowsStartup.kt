package dev.anchorwindows

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Secondary restore trigger (the primary is [AnchorWindowsInitListener]); both funnel into the
 * idempotent [AnchorWindowsService.scheduleRestoreOnce], which queues the restore behind tool
 * window initialization via ToolWindowManager.invokeLater.
 */
class AnchorWindowsStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        AnchorWindowsService.getInstance(project).scheduleRestoreOnce("ProjectActivity")
    }
}
