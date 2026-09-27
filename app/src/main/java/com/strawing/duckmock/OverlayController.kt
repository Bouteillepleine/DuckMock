package com.strawing.duckmock

import android.app.Service
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class OverlayController(private val service: Service) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private val ui = Handler(Looper.getMainLooper())

    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var pad: JoystickView? = null
    private var readout: TextView? = null
    private var chip: TextView? = null
    private var toggle: TextView? = null
    private var grip: TextView? = null
    private var collapsed = false
    private var padSize = SpoofPrefs.PAD_DEFAULT

    val up: Boolean
        get() = root != null

    fun show(): Boolean {
        if (root != null) return true
        if (!Settings.canDrawOverlays(service)) return false
        val view = build()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = SpoofPrefs.overlayX(service)
            y = SpoofPrefs.overlayY(service)
        }
        val added = runCatching { wm?.addView(view, lp) }.isSuccess
        if (!added) return false
        root = view
        params = lp
        JoyState.overlayUp = true
        refresh()
        return true
    }

    fun hide() {
        root?.let { view -> runCatching { wm?.removeView(view) } }
        root = null
        params = null
        pad = null
        readout = null
        chip = null
        grip = null
        toggle = null
        JoyState.overlayUp = false
        JoyState.release()
    }

    fun refresh() {
        if (root == null) return
        ui.post {
            val target = readout ?: return@post
            val pace = SpoofPrefs.pace(service)
            chip?.text = pace.label
            pad?.latch = SpoofPrefs.latch(service)
            target.text = if (JoyState.pushing) {
                String.format(
                    Locale.ROOT,
                    "%.1f m/s · %03.0f°",
                    JoyState.speed,
                    JoyState.bearing,
                )
            } else {
                "Standing still"
            }
        }
    }

    private fun build(): LinearLayout {
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(24f)
                setColor(CARD)
                setStroke(dp(1f).toInt(), EDGE)
            }
            setPadding(dp(8f).toInt(), dp(6f).toInt(), dp(8f).toInt(), dp(8f).toInt())
        }
        card.addView(handleRow())
        card.addView(padRow())
        card.addView(readoutRow())
        return card
    }

    private fun handleRow(): View {
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f).toInt(), dp(2f).toInt(), dp(2f).toInt(), dp(6f).toInt())
        }
        row.addView(
            ImageView(service).apply {
                setImageResource(R.drawable.ic_joystick)
                imageTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                layoutParams = LinearLayout.LayoutParams(dp(20f).toInt(), dp(20f).toInt())
                    .apply { marginEnd = dp(8f).toInt() }
            }
        )
        chip = TextView(service).apply {
            text = SpoofPrefs.pace(service).label
            setTextColor(ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = dp(11f)
                setColor(CHIP)
            }
            setPadding(dp(10f).toInt(), dp(4f).toInt(), dp(10f).toInt(), dp(4f).toInt())
            isClickable = true
            setOnClickListener { cyclePace() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }
        row.addView(chip)
        toggle = TextView(service).apply {
            text = "–"
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(dp(28f).toInt(), dp(26f).toInt())
            setOnClickListener { setCollapsed(!collapsed) }
        }
        row.addView(toggle)
        row.addView(TextView(service).apply {
            text = "✕"
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(dp(28f).toInt(), dp(26f).toInt())
            // Closing is meant to stick, so the stick does not return on the next session.
            setOnClickListener {
                SpoofPrefs.setJoystick(service, false)
                hide()
            }
        })
        attachDrag(row)
        return row
    }

    private fun padRow(): View {
        val holder = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        padSize = SpoofPrefs.padSize(service)
        val view = JoystickView(service).apply {
            accent = ACCENT
            ring = EDGE
            face = FACE
            latch = SpoofPrefs.latch(service)
            layoutParams = LinearLayout.LayoutParams(
                dp(padSize.toFloat()).toInt(),
                dp(padSize.toFloat()).toInt(),
            )
            onAim = { east, north ->
                JoyState.aim(east, north)
                if (!JoyState.pushing) {
                    JoyState.speed = 0f
                    refresh()
                }
            }
        }
        pad = view
        holder.addView(view)
        return holder
    }

    private fun readoutRow(): View {
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f).toInt(), dp(6f).toInt(), 0, 0)
        }
        readout = TextView(service).apply {
            text = "Standing still"
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }
        row.addView(readout)
        grip = TextView(service).apply {
            text = "⤡"
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(26f).toInt(), dp(22f).toInt())
        }
        attachResize(grip!!)
        row.addView(grip)
        return row
    }

    private fun attachResize(handle: View) {
        var startSize = 0
        var downX = 0f
        var downY = 0f
        handle.setOnTouchListener { _, event ->
            val view = pad ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startSize = padSize
                    downX = event.rawX
                    downY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val density = service.resources.displayMetrics.density
                    val moved = ((event.rawX - downX) + (event.rawY - downY)) / 2f / density
                    padSize = (startSize + moved).toInt()
                        .coerceIn(SpoofPrefs.PAD_MIN, SpoofPrefs.PAD_MAX)
                    val side = dp(padSize.toFloat()).toInt()
                    view.layoutParams = LinearLayout.LayoutParams(side, side)
                    view.requestLayout()
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    SpoofPrefs.setPadSize(service, padSize)
                    true
                }

                else -> false
            }
        }
    }

    private fun setCollapsed(value: Boolean) {
        collapsed = value
        if (value) {
            JoyState.release()
            pad?.reset()
        }
        pad?.parent?.let { (it as View).visibility = if (value) View.GONE else View.VISIBLE }
        (readout?.parent as? View)?.visibility = if (value) View.GONE else View.VISIBLE
        chip?.visibility = if (value) View.GONE else View.VISIBLE
        toggle?.text = if (value) "+" else "–"
        refresh()
    }

    private fun cyclePace() {
        val next = Pace.at((SpoofPrefs.pace(service).ordinal + 1) % Pace.entries.size)
        SpoofPrefs.setPace(service, next)
        chip?.text = next.label
        refresh()
    }

    private fun attachDrag(handle: View) {
        var originX = 0
        var originY = 0
        var downX = 0f
        var downY = 0f
        handle.setOnTouchListener { _, event ->
            val lp = params ?: return@setOnTouchListener false
            val view = root ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    originX = lp.x
                    originY = lp.y
                    downX = event.rawX
                    downY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val metrics = service.resources.displayMetrics
                    val maxX = (metrics.widthPixels - view.width).coerceAtLeast(0)
                    val maxY = (metrics.heightPixels - view.height).coerceAtLeast(0)
                    lp.x = (originX + (event.rawX - downX).toInt()).coerceIn(0, maxX)
                    lp.y = (originY + (event.rawY - downY).toInt()).coerceIn(0, maxY)
                    runCatching { wm?.updateViewLayout(view, lp) }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    SpoofPrefs.setOverlayPosition(service, lp.x, lp.y)
                    true
                }

                else -> false
            }
        }
    }

    private fun dp(value: Float): Float = value * service.resources.displayMetrics.density

    private companion object {
        const val CARD = 0xE60E1A15.toInt()
        const val EDGE = 0x40FFFFFF
        const val FACE = 0x14FFFFFF
        const val MUTED = 0xFF8FA79C.toInt()
        const val ACCENT = 0xFF3DDC97.toInt()
        const val CHIP = 0x243DDC97
    }
}
