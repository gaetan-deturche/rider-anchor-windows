# Anchor Windows

Rider/IntelliJ plugin that adds **Window | New Anchor Window**: an empty detached IDE frame
with its own tool window pane, so standard tool windows (Explorer, Build output, Debug, ...)
can be docked into a secondary window **without** the classic workaround of dragging a dummy
editor tab out to serve as an anchor.

## How it works

Since platform 2023.1, a detached editor window (`DockWindow`) gets its own `ToolWindowPane`,
which is the only secondary-frame type that accepts docked tool windows. The platform creates
that pane for any `DockWindow` whose content is not a singleton editor — the editor itself is
irrelevant. This plugin therefore rides the exact same code path with an editor-less container:

- `AnchorDockContainer` — implements the **public** `com.intellij.ui.docking.DockContainer.Persistent`
  API. It is a plain placeholder panel for the frame's center area, denies all drops, and holds
  no `EditorsSplitters` — so navigation / "jump to source" can never open files in an anchor
  window (files always go to the main frame), unlike the dummy-editor trick.
- `NewAnchorWindowAction` — calls `DockManagerImpl.createNewDockContainerFor(content, point)`.
  The platform then creates the `DockWindow`, the `ToolWindowPane`, registers the pane with
  `ToolWindowManagerImpl`, and wires stripe drag & drop. Tool windows are moved in by dragging
  their stripe button / title bar onto the anchor window's edges.
- Persistence is owned by the plugin (`AnchorWindowsService`), NOT the platform. The platform's
  `DockContainer.Persistent` route was tried in 0.1.x and is unfixably racy: tool windows on
  secondary panes are registered after the reopening-editors job, and pane teardown at project
  close hides tool windows and strips their stripe buttons — either could corrupt the layout.
  Instead the service continuously snapshots (debounced, plus once at `projectClosing` before
  the platform teardown) each anchor window's frame bounds and tool window placements into its
  own workspace component. On project open, `ToolWindowManager.invokeLater` — guaranteed to run
  after tool window initialization — recreates the frames and moves the tool windows in with a
  single `setLayout(modifiedLayoutCopy)` diff, the same code path as the built-in restore-layout
  feature. This also self-repairs layouts corrupted by a previous session.

### Internal-API surface (version-fragility)

Only one internal touchpoint: the cast to `com.intellij.ui.docking.impl.DockManagerImpl`
(`@ApiStatus.Internal`, but a public class/method). Everything else is public docking API.
Checked against intellij-community branch `261` (Rider 2026.1).

### Known limitations

- The center (document) area shows a hint label only while the frame is empty; once any tool
  window is docked, the placeholder hides itself and the tool windows absorb all its space
  (an invisible inner component gets zero size in `ThreeComponentsSplitter.doLayout`).
- Anchor windows reappear a moment after project open (once tool window initialization is done),
  not instantly with the main frame. Until then the anchored tool windows sit hidden or on the
  main frame; the restore pass then moves them back.
- Closing an anchor window sends its docked tool windows back to the main pane (platform
  behavior in `ToolWindowManagerImpl.addToolWindowPane`'s disposable).

## Build

```
./gradlew buildPlugin
```

Produces `build/distributions/anchor-windows-<version>.zip`; install via
Settings | Plugins | ⚙ | Install Plugin from Disk. Gradle must run on JBR/JDK 25
(same toolchain setup as rider-mcp-plugin).
