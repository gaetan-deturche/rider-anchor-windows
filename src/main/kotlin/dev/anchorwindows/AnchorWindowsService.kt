package dev.anchorwindows

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WINDOW_INFO_DEFAULT_TOOL_WINDOW_PANE_ID
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.openapi.wm.ex.ToolWindowManagerEx
import com.intellij.openapi.wm.impl.ToolWindowManagerImpl
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.openapi.wm.safeToolWindowPaneId
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.docking.DockManager
import com.intellij.ui.docking.impl.DockManagerImpl
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.abs

private val LOG = logger<AnchorWindowsService>()

@Service(Service.Level.PROJECT)
@State(name = "AnchorWindows", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class AnchorWindowsService(private val project: Project) : PersistentStateComponent<AnchorWindowsState>, Disposable {
    private var state = AnchorWindowsState()
    private var registered = false

    // Set when the project starts closing: the platform then tears the anchor windows down
    // (hiding their tool windows, stripping their stripe buttons, resetting their pane ids) —
    // none of that must overwrite the last good snapshot.
    private var frozen = false

    private val containers = mutableListOf<AnchorDockContainer>()

    private val restoreScheduled = AtomicBoolean(false)

    // Snapshots are suppressed until the startup restore has run: during project open, tool
    // window events fire while no anchor container exists yet, and an early snapshot would
    // overwrite the loaded state with "no windows" before restore ever reads it.
    private var restored = false

    // Saved-visible tools that were not yet available at restore time (Rider makes most tool
    // windows available only once the solution has loaded); setLayout force-clears visibility
    // for unavailable tools, so these are shown when their availability flips.
    private val pendingShow = mutableSetOf<String>()

    // Saved sizes, applied explicitly after restore/deferred shows: setLayout's own size pass
    // only fires when the weight differs between old and new layout, but the platform-persisted
    // layout already carries the same weights while the fresh pane's splitters carry none.
    private var savedToolSizes = mapOf<String, AnchorToolState>()

    private val snapshotTimer = Timer(500) { snapshot() }.apply { isRepeats = false }

    init {
        // Subscribed at service creation, i.e. before any DockWindow exists — message bus
        // dispatch order guarantees this snapshot runs before FrameWrapper's own projectClosing
        // handler closes the anchor windows.
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(ProjectCloseListener.TOPIC, object : ProjectCloseListener {
                override fun projectClosing(project: Project) {
                    if (project === this@AnchorWindowsService.project && !frozen) {
                        snapshot()
                        frozen = true
                    }
                }
            })
        project.messageBus.connect(this).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun stateChanged(toolWindowManager: ToolWindowManager) {
                processPendingShows()
                scheduleSnapshot()
            }
        })
    }

    override fun getState(): AnchorWindowsState = state

    override fun loadState(loaded: AnchorWindowsState) {
        LOG.info("loadState: ${loaded.windows.size} anchor window(s), ${loaded.windows.sumOf { it.tools.size }} tool assignment(s)")
        state = loaded
    }

    /**
     * Rider's built-in "tool window layout per application" option
     * (RiderPerAppSettingsManager.toolWindowsPerApp, an app-level options file). When enabled,
     * anchor windows follow the same policy: state lives in [AnchorWindowsAppStore] and is
     * shared by all solutions. Read once per session — the built-in layout sharing itself only
     * settles on restart too.
     */
    private val perApp: Boolean by lazy {
        try {
            val file = Path.of(PathManager.getOptionsPath(), "RiderPerAppSettingsManager.xml")
            if (!Files.exists(file)) return@lazy false
            val enabled = JDOMUtil.load(file)
                .getChildren("component").firstOrNull { it.getAttributeValue("name") == "RiderPerAppSettingsManager" }
                ?.getChildren("option")?.firstOrNull { it.getAttributeValue("name") == "toolWindowsPerApp" }
                ?.getAttributeValue("value") == "true"
            LOG.info("toolWindowsPerApp=$enabled")
            enabled
        }
        catch (e: Exception) {
            LOG.warn("could not read RiderPerAppSettingsManager.xml, using per-project state", e)
            false
        }
    }

    /** Windows to restore from: the app store when sharing is on (seeded from the per-project
     *  state the first time), the project workspace state otherwise. */
    private fun readWindows(): List<AnchorWindowState> {
        if (perApp) {
            val appWindows = AnchorWindowsAppStore.getInstance().windows
            if (appWindows.isNotEmpty()) return appWindows.toList()
        }
        return state.windows.toList()
    }

    private fun writeWindows(windows: MutableList<AnchorWindowState>) {
        if (perApp) {
            AnchorWindowsAppStore.getInstance().windows = windows
        }
        else {
            state.windows = windows
        }
    }

    /**
     * Queues [restoreAnchorWindows] behind tool window initialization, once. Called from two
     * independent triggers (the declarative ToolWindowManagerListener and the ProjectActivity)
     * because either one alone has been observed not to fire/execute in Rider.
     */
    fun scheduleRestoreOnce(trigger: String) {
        if (!restoreScheduled.compareAndSet(false, true)) return
        LOG.info("scheduling anchor window restore (trigger: $trigger)")
        ToolWindowManager.getInstance(project).invokeLater {
            try {
                restoreAnchorWindows()
            }
            catch (e: Throwable) {
                LOG.error("anchor window restore failed", e)
            }
        }
    }

    fun createAnchorWindow() {
        // If the user beats the startup restore to it, apply the saved state first.
        restoreAnchorWindows()
        ensureFactoryRegistered()
        (DockManager.getInstance(project) as DockManagerImpl).createNewDockContainerFor(AnchorDockableContent(), creationPoint())
        retitleAnchorFrames()
        installFocusSync()
        scheduleSnapshot()
    }

    /**
     * Recreates the anchor windows recorded in [state] and moves their tool windows back in with
     * a single setLayout() diff — the same code path as the platform's own "restore layout".
     * Must run after tool window initialization (ToolWindowManager.invokeLater); at that point
     * any tool window whose saved layout still points at a stale pane id has been placed on the
     * main frame by the fallback in getToolWindowPane(), so UI and model are repairable from here
     * whatever state the previous session left behind.
     */
    fun restoreAnchorWindows() {
        if (restored) return
        restored = true
        val savedWindows = readWindows()
        LOG.info("restoreAnchorWindows: ${savedWindows.size} saved window(s), perApp=$perApp")
        if (savedWindows.isEmpty()) return
        ensureFactoryRegistered()

        val dockManager = DockManager.getInstance(project) as DockManagerImpl
        val toolWindowManager = ToolWindowManagerEx.getInstanceEx(project)

        rehomeToolsFromStalePanes(toolWindowManager, savedWindows)

        val newLayout = toolWindowManager.getLayout().copy()
        var layoutChanged = false

        for (saved in savedWindows) {
            dockManager.createNewDockContainerFor(AnchorDockableContent(), creationPoint())
            val container = containers.lastOrNull() ?: continue
            val frame = SwingUtilities.getWindowAncestor(container.containerComponent)
            if (frame != null && saved.width > 0 && saved.height > 0) {
                frame.bounds = Rectangle(saved.x, saved.y, saved.width, saved.height)
            }
            val paneId = container.paneId()
            LOG.info("recreated anchor window: paneId=$paneId, bounds=${frame?.bounds}, ${saved.tools.size} tool(s) to move in")
            if (paneId == null) continue
            for (tool in saved.tools) {
                val info = newLayout.getInfo(tool.id)
                if (info == null) {
                    LOG.info("  tool '${tool.id}' has no layout info, skipped")
                    continue
                }
                info.toolWindowPaneId = paneId
                info.anchor = ToolWindowAnchor.fromText(tool.anchor)
                info.order = tool.order
                info.isSplit = tool.split
                info.isVisible = tool.visible
                info.isShowStripeButton = true
                info.weight = tool.weight
                info.sideWeight = tool.sideWeight
                layoutChanged = true
            }
        }

        LOG.info("applying restored layout (layoutChanged=$layoutChanged)")
        if (layoutChanged) {
            toolWindowManager.setLayout(newLayout)
        }

        // setLayout clears visibility for tools that are not (yet) available; show the available
        // ones now and queue the rest for when their availability flips.
        for (saved in savedWindows) {
            for (tool in saved.tools) {
                if (!tool.visible) continue
                val toolWindow = toolWindowManager.getToolWindow(tool.id)
                if (toolWindow != null && toolWindow.isAvailable) {
                    if (!toolWindow.isVisible) toolWindow.show()
                }
                else {
                    LOG.info("  '${tool.id}' not available yet, queueing show for later")
                    pendingShow.add(tool.id)
                }
            }
        }

        savedToolSizes = savedWindows.flatMap { it.tools }.associateBy { it.id }
        scheduleSizePass()

        retitleAnchorFrames()
        installFocusSync()
        snapshot()
    }

    /** Applies saved sizes once the layout has settled; re-scheduled after deferred shows. */
    private fun scheduleSizePass() {
        Timer(600) { applySavedSizes() }.apply { isRepeats = false }.start()
    }

    private fun applySavedSizes() {
        if (project.isDisposed) return
        for ((id, saved) in savedToolSizes) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(id) as? ToolWindowEx ?: continue
            if (!toolWindow.isVisible) continue
            val decorator = toolWindow.decorator
            val rootPane = SwingUtilities.getRootPane(decorator) ?: continue
            if (rootPane.width == 0 || rootPane.height == 0) continue
            val anchor = ToolWindowAnchor.fromText(saved.anchor)
            val horizontal = anchor == ToolWindowAnchor.TOP || anchor == ToolWindowAnchor.BOTTOM
            val desired = if (horizontal) (rootPane.height * saved.weight).toInt() else (rootPane.width * saved.weight).toInt()
            val current = if (horizontal) decorator.height else decorator.width
            val delta = desired - current
            if (abs(delta) > 5) {
                LOG.info("resizing '$id': $current -> $desired px (weight=${saved.weight})")
                if (horizontal) toolWindow.stretchHeight(delta) else toolWindow.stretchWidth(delta)
            }
        }
    }

    private fun processPendingShows() {
        if (pendingShow.isEmpty()) return
        var shown = false
        val iterator = pendingShow.iterator()
        while (iterator.hasNext()) {
            val id = iterator.next()
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(id) ?: continue
            if (toolWindow.isAvailable) {
                LOG.info("showing '$id' now that it became available")
                iterator.remove()
                toolWindow.show()
                shown = true
            }
        }
        if (shown) {
            scheduleSizePass()
        }
    }

    /**
     * Pre-pass, run BEFORE the anchor frames are created: tools whose model still points at a
     * pane id from the previous session were placed on the main frame by the fallback in
     * getToolWindowPane(), so model and UI diverge. Left as-is, the later move would remove
     * decorators from the freshly created pane (which reuses the same id) instead of the main
     * frame, leaving a dead component reserving empty space beside the editor. Re-homing the
     * model to the default pane first makes every subsequent setLayout() move operate on the
     * pane the decorator is actually in.
     */
    private fun rehomeToolsFromStalePanes(toolWindowManager: ToolWindowManagerEx, savedWindows: List<AnchorWindowState>) {
        val managerImpl = toolWindowManager as? ToolWindowManagerImpl ?: return
        val preLayout = toolWindowManager.getLayout().copy()
        var changed = false
        for (saved in savedWindows) {
            for (tool in saved.tools) {
                val info = preLayout.getInfo(tool.id) ?: continue
                val paneId = info.safeToolWindowPaneId
                if (paneId != WINDOW_INFO_DEFAULT_TOOL_WINDOW_PANE_ID && managerImpl.getToolWindowPane(paneId).paneId != paneId) {
                    LOG.info("  re-homing '${tool.id}' from stale pane '$paneId' to the default pane")
                    info.toolWindowPaneId = WINDOW_INFO_DEFAULT_TOOL_WINDOW_PANE_ID
                    changed = true
                }
            }
        }
        if (changed) {
            toolWindowManager.setLayout(preLayout)
        }
    }

    /**
     * Registering the factory only routes createNewDockContainerFor() to it; the container is
     * deliberately not DockContainer.Persistent, so the platform neither saves nor restores
     * anchor windows on its own.
     */
    fun ensureFactoryRegistered() {
        if (registered) return
        registered = true
        DockManager.getInstance(project).register(AnchorDockContainerFactory.TYPE, AnchorDockContainerFactory(project), this)
    }

    internal fun onContainerCreated(container: AnchorDockContainer) {
        containers.add(container)
    }

    internal fun onContainerDisposed(container: AnchorDockContainer) {
        containers.remove(container)
        // A window the user closed mid-session must drop out of the persisted state.
        scheduleSnapshot()
    }

    private fun scheduleSnapshot() {
        if (frozen || !restored) return
        snapshotTimer.restart()
    }

    private fun snapshot() {
        if (frozen || !restored || project.isDisposed) return
        val toolWindowManager = ToolWindowManager.getInstance(project) as? ToolWindowManagerEx ?: return
        val layoutInfos = toolWindowManager.getLayout().getInfos()
        val windows = mutableListOf<AnchorWindowState>()
        for (container in containers.toList()) {
            val frame = SwingUtilities.getWindowAncestor(container.containerComponent) ?: continue
            val paneId = container.paneId() ?: continue
            val windowState = AnchorWindowState().apply {
                x = frame.x
                y = frame.y
                width = frame.width
                height = frame.height
            }
            for ((toolWindowId, info) in layoutInfos) {
                if (info.safeToolWindowPaneId != paneId) continue
                windowState.tools.add(AnchorToolState().apply {
                    id = toolWindowId
                    anchor = info.anchor.toString()
                    order = info.order
                    split = info.isSplit
                    // A queued show is a not-yet-realized "visible" — don't let a snapshot
                    // taken before availability flips erase the saved intent.
                    visible = info.isVisible || toolWindowId in pendingShow
                    weight = measureWeight(toolWindowId, info.anchor) ?: info.weight
                    sideWeight = info.sideWeight
                })
            }
            windows.add(windowState)
        }
        writeWindows(windows)
    }

    // ------------------------------------------------------------------------------------
    // Focus-group syncing: when enabled, focusing any window of the group (main frame or an
    // anchor window) raises the others in z-order without activating them, so the group
    // alt-tabs/clicks back as one unit.
    // ------------------------------------------------------------------------------------

    private val focusSyncInstalled = WeakHashMap<Window, Boolean>()
    private var raisingGroup = false

    fun installFocusSync() {
        for (window in groupWindows()) {
            if (focusSyncInstalled.put(window, true) == null) {
                val listener = object : WindowAdapter() {
                    override fun windowGainedFocus(e: WindowEvent) = onGroupWindowFocused(e.window)
                    override fun windowIconified(e: WindowEvent) = onGroupWindowIconified(e.window, iconified = true)
                    override fun windowDeiconified(e: WindowEvent) = onGroupWindowIconified(e.window, iconified = false)
                }
                window.addWindowFocusListener(listener)
                window.addWindowListener(listener)
            }
        }
    }

    /**
     * Shares minimize/restore across the group (same toggle as focus). The per-window state
     * checks make propagation idempotent: the events our own setExtendedState calls trigger
     * find every other window already in the target state and stop, so no feedback loop.
     */
    private fun onGroupWindowIconified(source: Window, iconified: Boolean) {
        if (!isFocusSyncEnabled()) return
        for (window in groupWindows()) {
            if (window === source) continue
            val frame = window as? Frame ?: continue
            val isIconified = frame.extendedState and Frame.ICONIFIED != 0
            if (iconified && !isIconified) {
                frame.extendedState = frame.extendedState or Frame.ICONIFIED
            }
            else if (!iconified && isIconified) {
                frame.extendedState = frame.extendedState and Frame.ICONIFIED.inv()
            }
        }
    }

    private fun groupWindows(): List<Window> {
        val windows = mutableListOf<Window>()
        WindowManager.getInstance().getFrame(project)?.let { windows.add(it) }
        for (container in containers.toList()) {
            SwingUtilities.getWindowAncestor(container.containerComponent)?.let { windows.add(it) }
        }
        return windows
    }

    private fun onGroupWindowFocused(focused: Window) {
        if (raisingGroup || !isFocusSyncEnabled()) return
        raisingGroup = true
        try {
            val group = groupWindows().filter {
                it.isShowing && ((it as? Frame)?.extendedState ?: 0) and Frame.ICONIFIED == 0
            }
            if (group.size < 2 || focused !in group) return
            for (window in group) {
                if (window !== focused) raiseWithoutActivation(window)
            }
            raiseWithoutActivation(focused)
        }
        finally {
            raisingGroup = false
        }
    }

    private fun raiseWithoutActivation(window: Window) {
        try {
            val hwnd = WinDef.HWND(Native.getComponentPointer(window))
            User32.INSTANCE.SetWindowPos(hwnd, HWND_TOP, 0, 0, 0, 0, SWP_NOSIZE or SWP_NOMOVE or SWP_NOACTIVATE)
        }
        catch (e: Throwable) {
            // Non-Windows or JNA unavailable: best-effort Swing fallback (may briefly flick focus).
            val previous = window.isAutoRequestFocus
            window.isAutoRequestFocus = false
            window.toFront()
            window.isAutoRequestFocus = previous
        }
    }

    /**
     * The platform refuses to update info.weight when a pane's center (editor) component is
     * hidden ("Editor area is not visible, not updating tool window weights") — which is always
     * the case in anchor windows once tools are docked, because the placeholder hides itself.
     * So info.weight is stale for anchor tools; measure the decorator's real size instead.
     */
    private fun measureWeight(toolWindowId: String, anchor: ToolWindowAnchor): Float? {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(toolWindowId) as? ToolWindowEx ?: return null
        if (!toolWindow.isVisible) return null
        val decorator = try { toolWindow.decorator } catch (e: Exception) { return null }
        if (!decorator.isShowing) return null
        val rootPane = SwingUtilities.getRootPane(decorator) ?: return null
        val horizontal = anchor == ToolWindowAnchor.TOP || anchor == ToolWindowAnchor.BOTTOM
        val part = if (horizontal) decorator.height else decorator.width
        val total = if (horizontal) rootPane.height else rootPane.width
        if (part <= 0 || total <= 0) return null
        return part.toFloat() / total
    }

    /** Anchor frames have no editor to derive a title from; give them a recognizable one. */
    private fun retitleAnchorFrames() {
        for (container in containers) {
            (SwingUtilities.getWindowAncestor(container.containerComponent) as? JFrame)?.title = "Anchor Window"
        }
    }

    private fun creationPoint(): RelativePoint {
        val frame = WindowManager.getInstance().getFrame(project)
        return if (frame != null && frame.isShowing) {
            RelativePoint(frame, Point(frame.width / 2, frame.height / 2))
        }
        else {
            RelativePoint(MouseInfo.getPointerInfo().location)
        }
    }

    override fun dispose() {
        snapshotTimer.stop()
        containers.clear()
    }

    companion object {
        const val FOCUS_SYNC_PROPERTY = "dev.anchorwindows.followFocus"

        fun isFocusSyncEnabled(): Boolean = PropertiesComponent.getInstance().getBoolean(FOCUS_SYNC_PROPERTY, false)

        fun getInstance(project: Project): AnchorWindowsService = project.service()
    }
}

private val HWND_TOP = WinDef.HWND(Pointer.createConstant(0L))
private const val SWP_NOSIZE = 0x0001
private const val SWP_NOMOVE = 0x0002
private const val SWP_NOACTIVATE = 0x0010
