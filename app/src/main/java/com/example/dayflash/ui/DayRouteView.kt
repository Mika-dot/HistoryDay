package com.example.dayflash.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.dayflash.R

class DayRouteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.primary)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(3f)
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.primary)
        style = Paint.Style.FILL
    }
    private var points: List<Pair<Double, Double>> = emptyList()

    fun submit(items: List<Pair<Double, Double>>) {
        points = items
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return

        val padding = dp(24f)
        val availableWidth = (width - padding * 2).coerceAtLeast(1f)
        val availableHeight = (height - padding * 2).coerceAtLeast(1f)

        val minLat = points.minOf { it.first }
        val maxLat = points.maxOf { it.first }
        val minLon = points.minOf { it.second }
        val maxLon = points.maxOf { it.second }
        val latSpan = (maxLat - minLat).takeIf { it > 0.000001 }
        val lonSpan = (maxLon - minLon).takeIf { it > 0.000001 }

        fun project(point: Pair<Double, Double>): Pair<Float, Float> {
            val x = if (lonSpan == null) {
                width / 2f
            } else {
                padding + (((point.second - minLon) / lonSpan) * availableWidth).toFloat()
            }
            val y = if (latSpan == null) {
                height / 2f
            } else {
                padding + (((maxLat - point.first) / latSpan) * availableHeight).toFloat()
            }
            return x to y
        }

        val projected = points.map(::project)
        if (projected.size > 1) {
            val path = Path().apply {
                moveTo(projected.first().first, projected.first().second)
                projected.drop(1).forEach { lineTo(it.first, it.second) }
            }
            canvas.drawPath(path, routePaint)
        }

        projected.forEachIndexed { index, point ->
            val radius = if (index == 0 || index == projected.lastIndex) dp(7f) else dp(5f)
            canvas.drawCircle(point.first, point.second, radius, pointPaint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
