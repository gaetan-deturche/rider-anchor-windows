package dev.anchorwindows

import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection

/**
 * The plugin's own persisted model (workspace file): which anchor windows exist, their frame
 * bounds, and which tool windows live in them with their exact placement. The platform's
 * DockManager/ToolWindowManager persistence is NOT used for anchor windows — restoring through
 * it races tool window initialization at startup and is torn down destructively at close.
 */
class AnchorWindowsState {
    @get:XCollection(style = XCollection.Style.v2)
    var windows: MutableList<AnchorWindowState> = mutableListOf()
}

@Tag("window")
class AnchorWindowState {
    @get:Attribute
    var x: Int = 0

    @get:Attribute
    var y: Int = 0

    @get:Attribute
    var width: Int = 0

    @get:Attribute
    var height: Int = 0

    @get:XCollection(style = XCollection.Style.v2)
    var tools: MutableList<AnchorToolState> = mutableListOf()
}

@Tag("tool")
class AnchorToolState {
    @get:Attribute
    var id: String = ""

    @get:Attribute
    var anchor: String = "left"

    @get:Attribute
    var order: Int = -1

    @get:Attribute
    var split: Boolean = false

    @get:Attribute
    var visible: Boolean = false

    @get:Attribute
    var weight: Float = 0.33f

    @get:Attribute
    var sideWeight: Float = 0.5f
}
