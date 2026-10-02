package com.notesis

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop

internal val LocalSpotiPopupBackdrop = compositionLocalOf<Backdrop?> { null }

/** Popup and page are different Compose roots/windows. Backdrop 1.0.6's
 * positionInWindow fallback loses the popup's window offset; screen positions
 * preserve it. Only the page is sampled, so the menu cannot capture itself. */
internal class SpotiPopupBackdrop(
    private val source: LayerBackdrop,
    private val sourceOrigin: () -> Offset,
) : Backdrop {
    override val isCoordinatesDependent = true

    override fun DrawScope.drawBackdrop(
        density: Density, layoutCoordinates: LayoutCoordinates?, layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        if (layoutCoordinates == null || !layoutCoordinates.isAttached) return
        val shift = sourceOrigin() - layoutCoordinates.positionOnScreen()
        withTransform({ translate(shift.x, shift.y) }) { drawLayer(source.graphicsLayer) }
    }
}
