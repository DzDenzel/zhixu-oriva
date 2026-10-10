package com.planlock.design

/** Geometry in logical pixels; no Android or Compose types. */
object TimelineGeometry {
    const val HOUR_HEIGHT = 56f
    const val GAP = 6f
    const val MIN_HEIGHT = 36f
    data class Block(val top: Float, val left: Float, val width: Float, val height: Float)

    fun y(minute: Int, windowStart: Int) = (minute - windowStart) / 60f * HOUR_HEIGHT

    fun block(windowStart: Int, windowEnd: Int, start: Int, end: Int, availableWidth: Float, column: Int, columns: Int): Block {
        require(windowEnd > windowStart && availableWidth >= 0 && columns in 1..2 && column in 0 until columns)
        val width = ((availableWidth - GAP * (columns - 1)) / columns).coerceAtLeast(0f)
        val visibleStart = start.coerceIn(windowStart, windowEnd)
        val visibleEnd = end.coerceIn(visibleStart, windowEnd)
        return Block(y(visibleStart, windowStart), column * (width + GAP), width,
            y(visibleEnd, visibleStart).coerceAtLeast(MIN_HEIGHT))
    }
}
