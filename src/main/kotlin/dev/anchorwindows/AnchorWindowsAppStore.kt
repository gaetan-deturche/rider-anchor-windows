package dev.anchorwindows

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Application-level anchor window state, used instead of the per-project workspace state when
 * Rider's built-in "tool window layout per application" option is enabled
 * (RiderPerAppSettingsManager.toolWindowsPerApp) — anchor windows then behave like the rest of
 * the layout and follow the user across solutions.
 */
@Service(Service.Level.APP)
@State(name = "AnchorWindowsApp", storages = [Storage("anchorWindows.xml")])
class AnchorWindowsAppStore : PersistentStateComponent<AnchorWindowsState> {
    private var state = AnchorWindowsState()

    override fun getState(): AnchorWindowsState = state

    override fun loadState(loaded: AnchorWindowsState) {
        state = loaded
    }

    var windows: MutableList<AnchorWindowState>
        get() = state.windows
        set(value) {
            state.windows = value
        }

    companion object {
        fun getInstance(): AnchorWindowsAppStore = service()
    }
}
