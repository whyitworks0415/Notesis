package com.notesis

/**
 * How the note screen is split: a tree whose leaves are panes and whose
 * branches lay their children out side by side (split by an up-and-down
 * line) or one above the other. Pane 0 is always the note that was opened;
 * every other pane shows a note of its own. Pure, so it can be tested.
 */
internal sealed class SplitNode {
    data class Pane(val id: Int) : SplitNode()

    data class Branch(
        /** True: children left to right. False: top to bottom. */
        val sideBySide: Boolean,
        val children: List<SplitNode>,
        /** Each child's share of the branch, in the children's order; any scale. */
        val weights: List<Float> = List(children.size) { 1f },
    ) : SplitNode() {
        init {
            require(children.size >= 2) { "A branch splits into two or more" }
            require(weights.size == children.size)
        }
    }
}

/** A rectangle in pixels, as the layout hands them out. */
internal data class SplitRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * A seam between two neighbouring children of a branch: [path] is the
 * branch's place in the tree (child indexes from the root), [index] the
 * first of the two children, [rect] where the handle is drawn.
 */
internal data class SplitSeam(
    val path: List<Int>,
    val index: Int,
    val sideBySide: Boolean,
    val rect: SplitRect,
    /** The branch's length along the way it splits, for turning a drag into a share of it. */
    val length: Float,
)

/** The ready-made splits, as offered in the split panel. */
internal enum class SplitPreset(val label: String) {
    SIDE_BY_SIDE("Side by side"),
    STACKED("Top and bottom"),
    THREE_SIDE_BY_SIDE("Three side by side"),
    RIGHT_SPLIT("Side by side, right split"),
    LEFT_SPLIT("Side by side, left split"),
    BOTTOM_SPLIT("Top and bottom, bottom split"),
    TOP_SPLIT("Top and bottom, top split"),
    FOUR("Four"),
    ;

    /** The tree for this preset: pane 0 where the note already is, the others numbered after it. */
    fun tree(): SplitNode = when (this) {
        SIDE_BY_SIDE -> SplitNode.Branch(true, listOf(p(0), p(1)))
        STACKED -> SplitNode.Branch(false, listOf(p(0), p(1)))
        THREE_SIDE_BY_SIDE -> SplitNode.Branch(true, listOf(p(0), p(1), p(2)))
        RIGHT_SPLIT -> SplitNode.Branch(true, listOf(p(0), SplitNode.Branch(false, listOf(p(1), p(2)))))
        LEFT_SPLIT -> SplitNode.Branch(true, listOf(SplitNode.Branch(false, listOf(p(0), p(2))), p(1)))
        BOTTOM_SPLIT -> SplitNode.Branch(false, listOf(p(0), SplitNode.Branch(true, listOf(p(1), p(2)))))
        TOP_SPLIT -> SplitNode.Branch(false, listOf(SplitNode.Branch(true, listOf(p(0), p(2))), p(1)))
        FOUR -> SplitNode.Branch(true, listOf(
            SplitNode.Branch(false, listOf(p(0), p(2))),
            SplitNode.Branch(false, listOf(p(1), p(3))),
        ))
    }

    private fun p(id: Int) = SplitNode.Pane(id)
}

/** The most panes a screen is split into: four canvases is already a lot of ink to draw. */
internal const val SPLIT_MAX_PANES = 4

/** No child of a branch is let shrink below this share of it. */
internal const val SPLIT_MIN_SHARE = 0.15f

internal fun SplitNode.paneIds(): List<Int> = when (this) {
    is SplitNode.Pane -> listOf(id)
    is SplitNode.Branch -> children.flatMap { it.paneIds() }
}

/**
 * Where each pane goes in [area], with [gap] pixels between neighbours for
 * the seam's handle.
 */
internal fun SplitNode.layout(area: SplitRect, gap: Float): Map<Int, SplitRect> {
    val out = LinkedHashMap<Int, SplitRect>()
    fun place(node: SplitNode, rect: SplitRect) {
        when (node) {
            is SplitNode.Pane -> out[node.id] = rect
            is SplitNode.Branch -> childRects(node, rect, gap).forEachIndexed { i, r -> place(node.children[i], r) }
        }
    }
    place(this, area)
    return out
}

/** Every seam in the tree, with the place of the handle that moves it. */
internal fun SplitNode.seams(area: SplitRect, gap: Float): List<SplitSeam> {
    val out = ArrayList<SplitSeam>()
    fun walk(node: SplitNode, rect: SplitRect, path: List<Int>) {
        if (node !is SplitNode.Branch) return
        val rects = childRects(node, rect, gap)
        for (i in 0 until rects.lastIndex) {
            val a = rects[i]
            val b = rects[i + 1]
            out += SplitSeam(path, i, node.sideBySide,
                if (node.sideBySide) SplitRect(a.right, rect.top, b.left, rect.bottom)
                else SplitRect(rect.left, a.bottom, rect.right, b.top),
                if (node.sideBySide) rect.width else rect.height)
        }
        node.children.forEachIndexed { i, child -> walk(child, rects[i], path + i) }
    }
    walk(this, area, emptyList())
    return out
}

private fun childRects(node: SplitNode.Branch, rect: SplitRect, gap: Float): List<SplitRect> {
    val total = node.weights.sum().takeIf { it > 0f } ?: 1f
    val length = (if (node.sideBySide) rect.width else rect.height) - gap * (node.children.size - 1)
    var at = if (node.sideBySide) rect.left else rect.top
    return node.weights.map { weight ->
        val size = length * weight / total
        val r = if (node.sideBySide) SplitRect(at, rect.top, at + size, rect.bottom)
            else SplitRect(rect.left, at, rect.right, at + size)
        at += size + gap
        r
    }
}

/**
 * The tree after the seam between children [index] and [index] + 1 of the
 * branch at [path] has moved by [fraction] of that branch's length. Neither
 * child goes below [SPLIT_MIN_SHARE] of what the two share.
 */
internal fun SplitNode.moveSeam(path: List<Int>, index: Int, fraction: Float): SplitNode {
    if (path.isEmpty()) {
        val branch = this as? SplitNode.Branch ?: return this
        if (index !in 0 until branch.children.lastIndex) return this
        val total = branch.weights.sum()
        val pair = branch.weights[index] + branch.weights[index + 1]
        val shareOfPair = pair / total
        val first = (branch.weights[index] / total + fraction)
            .coerceIn(shareOfPair * SPLIT_MIN_SHARE, shareOfPair * (1f - SPLIT_MIN_SHARE))
        val weights = branch.weights.toMutableList()
        weights[index] = first * total
        weights[index + 1] = pair - first * total
        return branch.copy(weights = weights)
    }
    val branch = this as? SplitNode.Branch ?: return this
    val head = path.first()
    if (head !in branch.children.indices) return this
    val children = branch.children.toMutableList()
    children[head] = children[head].moveSeam(path.drop(1), index, fraction)
    return branch.copy(children = children)
}

/**
 * The tree with pane [id] split in two, it and a new pane [newId], laid
 * out [sideBySide] or one above the other; unchanged if it is already at
 * [SPLIT_MAX_PANES] or [id] is not in it.
 */
internal fun SplitNode.splitPane(id: Int, sideBySide: Boolean, newId: Int): SplitNode {
    if (paneIds().size >= SPLIT_MAX_PANES || id !in paneIds()) return this
    fun go(node: SplitNode): SplitNode = when (node) {
        is SplitNode.Pane -> if (node.id == id) SplitNode.Branch(sideBySide, listOf(node, SplitNode.Pane(newId))) else node
        is SplitNode.Branch -> {
            val index = node.children.indexOfFirst { it is SplitNode.Pane && it.id == id }
            if (index >= 0 && node.sideBySide == sideBySide) {
                // The same way as its branch: one more child of it, sharing the pane's room.
                val children = node.children.toMutableList().apply { add(index + 1, SplitNode.Pane(newId)) }
                val weights = node.weights.toMutableList().apply {
                    val half = this[index] / 2f
                    this[index] = half
                    add(index + 1, half)
                }
                node.copy(children = children, weights = weights)
            } else {
                node.copy(children = node.children.map(::go))
            }
        }
    }
    return go(this)
}

/**
 * The tree without pane [id]: its neighbours take its room, and a branch
 * left with one child becomes that child. Null when only pane 0 is left -
 * the screen is no longer split. Pane 0 itself cannot be closed.
 */
internal fun SplitNode.closePane(id: Int): SplitNode? {
    if (id == 0) return this
    fun go(node: SplitNode): SplitNode? = when (node) {
        is SplitNode.Pane -> if (node.id == id) null else node
        is SplitNode.Branch -> {
            val kept = node.children.mapIndexedNotNull { i, child -> go(child)?.let { it to node.weights[i] } }
            when (kept.size) {
                0 -> null
                1 -> kept.single().first
                else -> node.copy(children = kept.map { it.first }, weights = kept.map { it.second })
            }
        }
    }
    val left = go(this) ?: return null
    return if (left is SplitNode.Pane) null else left
}

/** The lowest pane number not yet in use, for a pane being added. */
internal fun SplitNode.nextPaneId(): Int {
    val used = paneIds().toSet()
    return generateSequence(1) { it + 1 }.first { it !in used }
}

/** The same tree with every branch shared evenly: its shape, for telling which preset it is. */
internal fun SplitNode.shape(): SplitNode = when (this) {
    is SplitNode.Pane -> this
    is SplitNode.Branch -> SplitNode.Branch(sideBySide, children.map { it.shape() })
}
