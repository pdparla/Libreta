package com.parla.libreta.core.ink

import kotlin.math.ceil
import kotlin.math.max

/**
 * The part of a page an image for the model shows ([area], in page units) and its [scale] in pixels per unit: what
 * turns a point the model names in the image back into a point on the page (docs/design/ai.md#clean-copy).
 */
data class InkFrame(val area: InkRect, val scale: Float) {

    val pixelWidth: Int get() = ceil(area.width * scale).toInt().coerceAtLeast(1)
    val pixelHeight: Int get() = ceil(area.height * scale).toInt().coerceAtLeast(1)

    /** The rectangle with corners ([x1], [y1]) and ([x2], [y2]), in pixels of the image and in either order, on the page. */
    fun toPage(x1: Float, y1: Float, x2: Float, y2: Float): InkRect {
        fun x(px: Float) = area.left + px.coerceIn(0f, pixelWidth.toFloat()) / scale
        fun y(py: Float) = area.top + py.coerceIn(0f, pixelHeight.toFloat()) / scale
        return InkRect(minOf(x(x1), x(x2)), minOf(y(y1), y(y2)), maxOf(x(x1), x(x2)), maxOf(y(y1), y(y2)))
    }

    /**
     * As [toPage], with the corners in thousandths of the image's width and height: the API may resize an image
     * before the model sees it, so the model is asked for proportions rather than pixels.
     */
    fun fromThousandths(x1: Float, y1: Float, x2: Float, y2: Float): InkRect =
        toPage(x1 * pixelWidth / 1000f, y1 * pixelHeight / 1000f, x2 * pixelWidth / 1000f, y2 * pixelHeight / 1000f)

    companion object {
        /** Pixels per unit the model gets, unless the longer side would pass [MAX_SIDE]. */
        const val SCALE = 2f
        const val MAX_SIDE = 1600

        /** The least white margin around a selection, in page units. */
        const val MARGIN = 16f

        /**
         * The frame for [selected]: with a white margin of [MARGIN] units, or 10% of its longer side. A selection
         * snapped to its strokes leaves letters touching the edges, and the model then reads nothing.
         */
        fun around(selected: InkRect): InkFrame {
            val margin = max(MARGIN, max(selected.width, selected.height) * 0.1f)
            val area = InkRect(selected.left - margin, selected.top - margin, selected.right + margin, selected.bottom + margin)
            return InkFrame(area, area.renderScale(SCALE, MAX_SIDE))
        }

        /** The frame for the whole of [page]. */
        fun ofPage(page: InkPage): InkFrame = around(InkRect(0f, 0f, page.width.toFloat(), page.height.toFloat()))
    }
}
