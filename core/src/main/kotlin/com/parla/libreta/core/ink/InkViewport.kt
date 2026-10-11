package com.parla.libreta.core.ink

import kotlin.math.min

/**
 * How a page is shown in a view (docs/09-zoom.md): scaled to fit at 1× and then zoomed up to [MAX_ZOOM], with
 * [originX], [originY] being where the page's top-left corner falls, in view pixels. Strokes stay in page units;
 * this only converts between the two.
 *
 * The page never leaves empty space it could fill: on an axis where it is smaller than the view it is centred
 * (horizontally) or kept at the top (vertically), and on an axis where it is larger it cannot be moved past its edges.
 */
class InkViewport(val pageWidth: Int, val pageHeight: Int, val viewWidth: Int, val viewHeight: Int) {

    init {
        require(pageWidth > 0 && pageHeight > 0 && viewWidth > 0 && viewHeight > 0) { "Empty page or view" }
    }

    /**
     * View pixels per page unit at 1×: the whole page fits, except an upright page in a landscape view, which fits
     * the width and is scrolled (docs/10-orientacion.md); whole, it would be too small to write on.
     */
    val fitScale: Float =
        if (viewWidth > viewHeight && pageHeight > pageWidth) viewWidth / pageWidth.toFloat()
        else min(viewWidth / pageWidth.toFloat(), viewHeight / pageHeight.toFloat())

    var zoom = 1f
        private set
    var originX = 0f
        private set
    var originY = 0f
        private set

    /** View pixels per page unit now. */
    val scale: Float get() = fitScale * zoom

    init {
        clamp()
    }

    fun toPageX(viewX: Float) = (viewX - originX) / scale
    fun toPageY(viewY: Float) = (viewY - originY) / scale
    fun toViewX(pageX: Float) = originX + pageX * scale
    fun toViewY(pageY: Float) = originY + pageY * scale

    /** The part of the page on screen, in page units. */
    fun visible(): InkRect = InkRect(
        toPageX(0f).coerceIn(0f, pageWidth.toFloat()),
        toPageY(0f).coerceIn(0f, pageHeight.toFloat()),
        toPageX(viewWidth.toFloat()).coerceIn(0f, pageWidth.toFloat()),
        toPageY(viewHeight.toFloat()).coerceIn(0f, pageHeight.toFloat()),
    )

    /** Zooms to [newZoom] keeping the page point under ([focusX], [focusY]) where it is on screen. */
    fun zoomAround(focusX: Float, focusY: Float, newZoom: Float) {
        val pageX = toPageX(focusX)
        val pageY = toPageY(focusY)
        zoom = newZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        originX = focusX - pageX * scale
        originY = focusY - pageY * scale
        clamp()
    }

    /** Moves the page by ([dx], [dy]) view pixels, within its edges. */
    fun panBy(dx: Float, dy: Float) {
        originX += dx
        originY += dy
        clamp()
    }

    /** Shows the top-left corner of the page at the current zoom, e.g. after changing page. */
    fun showTopLeft() {
        originX = 0f
        originY = 0f
        clamp()
    }

    /** The same zoom and the same page point at the centre, in a view of another size (a rotation). */
    fun resized(newViewWidth: Int, newViewHeight: Int): InkViewport {
        val centreX = toPageX(viewWidth / 2f)
        val centreY = toPageY(viewHeight / 2f)
        return InkViewport(pageWidth, pageHeight, newViewWidth, newViewHeight).also { it.zoomAround(newViewWidth / 2f, newViewHeight / 2f, zoom); it.centreOn(centreX, centreY) }
    }

    private fun centreOn(pageX: Float, pageY: Float) {
        originX = viewWidth / 2f - pageX * scale
        originY = viewHeight / 2f - pageY * scale
        clamp()
    }

    private fun clamp() {
        val width = pageWidth * scale
        val height = pageHeight * scale
        originX = if (width <= viewWidth) (viewWidth - width) / 2 else originX.coerceIn(viewWidth - width, 0f)
        originY = if (height <= viewHeight) 0f else originY.coerceIn(viewHeight - height, 0f)
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 4f

        /** The zoom levels the − and + buttons step through. */
        val STEPS = listOf(1f, 1.5f, 2f, 3f, 4f)

        /** The next level after [zoom] going [up] (or down), staying at the ends. */
        fun step(zoom: Float, up: Boolean): Float =
            if (up) STEPS.firstOrNull { it > zoom + 0.01f } ?: MAX_ZOOM else STEPS.lastOrNull { it < zoom - 0.01f } ?: MIN_ZOOM
    }
}
