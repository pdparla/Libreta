package com.parla.libreta.core.ink

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A size chosen with a slider of [STEPS] steps (docs/16-grosor.md), from [min] to [max] page units. It grows
 * exponentially, so there is as much travel between the thin sizes as between the thick ones.
 */
class InkSizes(val min: Float, val max: Float) {

    init {
        require(min > 0f && max > min) { "Sizes go from a positive minimum up" }
    }

    fun valueAt(progress: Int): Float = min * (max / min).pow(progress.coerceIn(0, STEPS).toFloat() / STEPS)

    fun progressOf(value: Float): Int = (ln(value.coerceIn(min, max) / min) / ln(max / min) * STEPS).roundToInt()

    fun coerce(value: Float): Float = value.coerceIn(min, max)

    companion object {
        const val STEPS = 1000

        /** Pen thickness on screen; 3.5 was the old "Medio". */
        val PEN = InkSizes(0.5f, 40f)

        /** Eraser radius on screen; 10 was the old "Mediano". */
        val ERASER = InkSizes(2f, 80f)
    }
}
