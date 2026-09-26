package com.strawing.duckmock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

class JoystickView(context: Context) : View(context) {

    var onAim: ((Float, Float) -> Unit)? = null

    var accent = 0xFF3DDC97.toInt()
        set(value) {
            field = value
            invalidate()
        }

    var ring = 0x40FFFFFF
        set(value) {
            field = value
            invalidate()
        }

    var face = 0x14FFFFFF
        set(value) {
            field = value
            invalidate()
        }

    var latch = false
        set(value) {
            field = value
            if (!value && !holding) aim(0f, 0f)
        }

    private var nx = 0f
    private var ny = 0f
    private var holding = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arrow = Path()

    init {
        isClickable = true
    }

    fun reset() = aim(0f, 0f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val want = dp(184f).toInt()
        val side = min(
            resolveSize(want, widthMeasureSpec),
            resolveSize(want, heightMeasureSpec),
        )
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) / 2f - dp(1.5f)
        val thumb = dp(26f)
        val travel = (base - thumb).coerceAtLeast(1f)

        fill.color = face
        canvas.drawCircle(cx, cy, base, fill)

        stroke.color = ring
        stroke.strokeWidth = dp(1.5f)
        canvas.drawCircle(cx, cy, base, stroke)
        canvas.drawCircle(cx, cy, travel * 0.42f, stroke)

        fill.color = ring
        val tip = base - dp(7f)
        for (turn in 0..3) {
            canvas.save()
            canvas.rotate(turn * 90f, cx, cy)
            arrow.reset()
            arrow.moveTo(cx, cy - tip)
            arrow.lineTo(cx - dp(5f), cy - tip + dp(8f))
            arrow.lineTo(cx + dp(5f), cy - tip + dp(8f))
            arrow.close()
            canvas.drawPath(arrow, fill)
            canvas.restore()
        }

        val tx = cx + nx * travel
        val ty = cy + ny * travel
        val live = holding || (latch && hypot(nx, ny) > 0.02f)

        if (live) {
            stroke.color = accent
            stroke.strokeWidth = dp(3f)
            stroke.alpha = 120
            canvas.drawLine(cx, cy, tx, ty, stroke)
            stroke.alpha = 255
        }

        fill.color = accent
        fill.alpha = if (live) 255 else 190
        canvas.drawCircle(tx, ty, thumb, fill)
        fill.alpha = 255

        fill.color = face
        canvas.drawCircle(tx, ty, dp(6f), fill)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        val cx = width / 2f
        val cy = height / 2f
        val travel = (min(width, height) / 2f - dp(26f) - dp(1.5f)).coerceAtLeast(1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                holding = true
                var dx = (event.x - cx) / travel
                var dy = (event.y - cy) / travel
                val reach = hypot(dx, dy)
                if (reach > 1f) {
                    dx /= reach
                    dy /= reach
                }
                aim(dx, dy)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                holding = false
                if (latch) invalidate() else aim(0f, 0f)
            }

            else -> return false
        }
        return true
    }

    private fun aim(x: Float, y: Float) {
        nx = x
        ny = y
        onAim?.invoke(x, -y)
        invalidate()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
