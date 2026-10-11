package com.parla.libreta.ui.ink

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow

/**
 * Panel of pen colours: a row of greys and, for each hue across the spectrum, a column from dark to light.
 * It opens above the toolbar and closes when a colour is chosen or the user taps outside.
 */
object ColorPalette {

    private val hues = listOf(0f, 20f, 38f, 52f, 85f, 125f, 165f, 190f, 212f, 235f, 270f, 310f)

    /** Saturation and value of each row of hues, from dark to light. */
    private val shades = listOf(0.9f to 0.38f, 0.9f to 0.62f, 0.85f to 0.85f, 0.6f to 0.95f, 0.32f to 1f)

    /** The colours shown, row by row. The first one, near-black, is the default pen colour. */
    val colors: List<List<Int>> = listOf(
        listOf(0xff202124.toInt()) + (1 until hues.size).map { gray(0.22f + 0.7f * it / (hues.size - 1)) },
    ) + shades.map { (saturation, value) -> hues.map { Color.HSVToColor(floatArrayOf(it, saturation, value)) } }

    val defaultColor: Int get() = colors[0][0]

    /** Opens the panel at the bottom left of [above] (the writing area, so it does not cover the toolbars). */
    fun show(above: View, selected: Int, onPick: (Int) -> Unit) {
        val context = above.context
        val density = context.resources.displayMetrics.density
        val cell = (40 * density).toInt()
        val gap = (4 * density).toInt()
        val popup = PopupWindow(context)
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(gap * 2, gap * 2, gap * 2, gap * 2)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(density.toInt().coerceAtLeast(1), 0x33000000)
                cornerRadius = 8 * density
            }
        }
        for (row in colors) {
            val line = LinearLayout(context)
            for (color in row) {
                line.addView(
                    swatch(context, color, color == selected, density).apply {
                        setOnClickListener {
                            popup.dismiss()
                            onPick(color)
                        }
                    },
                    LinearLayout.LayoutParams(cell, cell).apply { setMargins(gap / 2, gap / 2, gap / 2, gap / 2) },
                )
            }
            grid.addView(line)
        }
        popup.contentView = grid
        popup.width = ViewGroup.LayoutParams.WRAP_CONTENT
        popup.height = ViewGroup.LayoutParams.WRAP_CONTENT
        popup.isFocusable = true
        popup.isOutsideTouchable = true
        // On API 19 a popup only closes on an outside tap if it has a background.
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        grid.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val location = IntArray(2)
        above.getLocationOnScreen(location)
        val bottom = location[1] + above.height
        popup.showAtLocation(above, Gravity.TOP or Gravity.START, location[0] + gap * 2, bottom - grid.measuredHeight - gap * 2)
    }

    /** A round swatch; the selected one gets a blue ring. */
    fun swatchDrawable(color: Int, selected: Boolean, density: Float) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        val unit = density.toInt().coerceAtLeast(1)
        setStroke(if (selected) 3 * unit else unit, if (selected) 0xff1a73e8.toInt() else 0x33000000)
    }

    private fun swatch(context: Context, color: Int, selected: Boolean, density: Float) = View(context).apply {
        background = swatchDrawable(color, selected, density)
        contentDescription = String.format("#%06X", color and 0xffffff)
    }

    private fun gray(value: Float) = Color.HSVToColor(floatArrayOf(0f, 0f, value))
}
