package com.parla.libreta.core.ink

import org.junit.Assert.assertEquals
import org.junit.Test

class InkViewportTest {

    /** A 600×900 page in a 600×850 view: it fits by height, centred horizontally. */
    private fun viewport() = InkViewport(600, 900, 600, 850)

    @Test
    fun `at 1x the whole page fits, centred`() {
        val v = viewport()

        assertEquals(850f / 900f, v.scale, 0.0001f)
        assertEquals((600 - 600 * v.scale) / 2, v.originX, 0.001f)
        assertEquals(0f, v.originY, 0f)
        assertEquals(100f, v.toPageX(v.toViewX(100f)), 0.001f)
        assertEquals(InkRect(0f, 0f, 600f, 900f), v.visible())
    }

    @Test
    fun `zooming keeps the point under the fingers in place`() {
        val v = viewport()
        val pageX = v.toPageX(300f)
        val pageY = v.toPageY(400f)

        v.zoomAround(300f, 400f, 2f)

        assertEquals(2f, v.zoom, 0f)
        assertEquals(300f, v.toViewX(pageX), 0.01f)
        assertEquals(400f, v.toViewY(pageY), 0.01f)
    }

    @Test
    fun `zoom stays between 1x and 4x and the page cannot be dragged past its edges`() {
        val v = viewport()
        v.zoomAround(0f, 0f, 10f)
        assertEquals(4f, v.zoom, 0f)

        v.panBy(5000f, 5000f)
        assertEquals(0f, v.originX, 0f)
        assertEquals(0f, v.originY, 0f)

        v.panBy(-50000f, -50000f)
        assertEquals(600f, v.toViewX(600f), 0.01f)
        assertEquals(850f, v.toViewY(900f), 0.01f)

        v.zoomAround(0f, 0f, 0.1f)
        assertEquals(1f, v.zoom, 0f)
        assertEquals(0f, v.originY, 0f)
    }

    @Test
    fun `the visible part shrinks when zooming`() {
        val v = viewport()
        v.zoomAround(0f, 0f, 2f)
        v.showTopLeft()

        val visible = v.visible()
        assertEquals(0f, visible.left, 0.01f)
        assertEquals(0f, visible.top, 0.01f)
        assertEquals(600f / v.scale, visible.right, 0.01f)
        assertEquals(850f / v.scale, visible.bottom, 0.01f)
    }

    @Test
    fun `buttons step through the levels`() {
        assertEquals(1.5f, InkViewport.step(1f, up = true), 0f)
        assertEquals(4f, InkViewport.step(4f, up = true), 0f)
        assertEquals(2f, InkViewport.step(3f, up = false), 0f)
        assertEquals(2f, InkViewport.step(2.4f, up = false), 0f)
        assertEquals(1f, InkViewport.step(1f, up = false), 0f)
    }

    @Test
    fun `a resized view keeps the zoom and what was in the middle`() {
        val v = viewport()
        v.zoomAround(300f, 425f, 2f)
        val middleX = v.toPageX(300f)
        val middleY = v.toPageY(425f)

        val turned = v.resized(1024, 500)

        assertEquals(2f, turned.zoom, 0f)
        assertEquals(middleX, turned.toPageX(512f), 0.5f)
        assertEquals(middleY, turned.toPageY(250f), 0.5f)
    }

    @Test
    fun `an upright page in a landscape view fits the width and scrolls`() {
        val v = InkViewport(600, 851, 1024, 450)

        assertEquals(1024f / 600f, v.scale, 0.0001f)
        assertEquals(0f, v.originX, 0f)
        assertEquals(0f, v.originY, 0f)
        v.panBy(0f, -100000f)
        assertEquals(450f, v.toViewY(851f), 0.01f)
    }

    @Test
    fun `a landscape page in a landscape view fits whole`() {
        val v = InkViewport(851, 600, 1024, 450)

        assertEquals(450f / 600f, v.scale, 0.0001f)
    }
}
