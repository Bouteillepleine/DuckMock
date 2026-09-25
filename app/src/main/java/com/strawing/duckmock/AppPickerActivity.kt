package com.strawing.duckmock

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.R as MR

class AppPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_SELECTED = "selected"
        const val MODE_SPOOFERS = "spoofers"
        const val MODE_EXEMPT = "exempt"
    }

    private class Entry(val pkg: String, val label: String, val system: Boolean)

    private val all = ArrayList<Entry>()
    private val shown = ArrayList<Entry>()
    private val selected = LinkedHashSet<String>()
    private var mode = MODE_SPOOFERS
    private lateinit var adapter: Adapter
    private lateinit var list: ListView

    private val cOnSurface get() = MaterialColors.getColor(this, MR.attr.colorOnSurface, 0)
    private val cOnSurfaceVar get() = MaterialColors.getColor(this, MR.attr.colorOnSurfaceVariant, 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_SPOOFERS
        intent.getStringArrayListExtra(EXTRA_SELECTED)?.let { selected.addAll(it) }

        val title = if (mode == MODE_SPOOFERS) "Pick your spoofer" else "Always tell the truth"
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(8))
        }
        header.addView(text(title, 22f, cOnSurface, Typeface.BOLD))
        val search = EditText(this).apply {
            hint = "Search"
            setSingleLine()
            setTextColor(cOnSurface)
            setHintTextColor(cOnSurfaceVar)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
            })
        }
        header.addView(search)

        list = ListView(this).apply {
            divider = null
            dividerHeight = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        footer.addView(MaterialButton(this, null, MR.attr.materialButtonOutlinedStyle).apply {
            text = "Cancel"
            setOnClickListener { finish() }
        })
        footer.addView(MaterialButton(this).apply {
            text = "Save"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8) }
            setOnClickListener { save() }
        })

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(list)
            addView(footer)
        }
        setContentView(shell)
        ViewCompat.setOnApplyWindowInsetsListener(shell) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        adapter = Adapter()
        list.adapter = adapter
        load()
    }

    private fun load() {
        Thread {
            val pm = packageManager
            val entries = ArrayList<Entry>()
            val seen = HashSet<String>()
            for (info in runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())) {
                if (!seen.add(info.packageName)) continue
                val system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val label = runCatching { pm.getApplicationLabel(info).toString() }
                    .getOrDefault(info.packageName)
                entries.add(Entry(info.packageName, label, system))
            }
            entries.sortWith(
                compareBy<Entry> { it.system }
                    .thenBy { it.label.lowercase() }
            )
            runOnUiThread {
                all.clear()
                all.addAll(entries)
                filter("")
            }
        }.apply { isDaemon = true }.start()
    }

    private fun filter(query: String) {
        val needle = query.trim().lowercase()
        shown.clear()
        for (entry in all) {
            if (needle.isEmpty() ||
                entry.label.lowercase().contains(needle) ||
                entry.pkg.lowercase().contains(needle)
            ) {
                shown.add(entry)
            }
        }
        shown.sortWith(
            compareByDescending<Entry> { it.pkg in selected }
                .thenBy { it.system }
                .thenBy { it.label.lowercase() }
        )
        adapter.notifyDataSetChanged()
    }

    private fun save() {
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_MODE, mode)
                putStringArrayListExtra(EXTRA_SELECTED, ArrayList(selected))
            }
        )
        finish()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val entry = shown[position]
            val row = (convertView as? LinearLayout) ?: buildRow()
            val icon = row.getChildAt(0) as ImageView
            val texts = row.getChildAt(1) as LinearLayout
            val label = texts.getChildAt(0) as TextView
            val pkg = texts.getChildAt(1) as TextView
            val toggle = row.getChildAt(2) as MaterialSwitch

            label.text = entry.label
            pkg.text = entry.pkg
            icon.setImageDrawable(
                runCatching { packageManager.getApplicationIcon(entry.pkg) }.getOrNull()
            )
            toggle.setOnCheckedChangeListener(null)
            toggle.isChecked = entry.pkg in selected
            toggle.setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(entry.pkg) else selected.remove(entry.pkg)
            }
            row.setOnClickListener { toggle.isChecked = !toggle.isChecked }
            return row
        }

        private fun buildRow(): LinearLayout {
            val row = LinearLayout(this@AppPickerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
            }
            row.addView(ImageView(this@AppPickerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    marginEnd = dp(14)
                }
            })
            val texts = LinearLayout(this@AppPickerActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(text("", 15f, cOnSurface))
            texts.addView(text("", 12f, cOnSurfaceVar))
            row.addView(texts)
            row.addView(MaterialSwitch(this@AppPickerActivity))
            return row
        }
    }

    private fun text(
        value: String,
        size: Float,
        color: Int,
        style: Int = Typeface.NORMAL,
    ): TextView = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (style != Typeface.NORMAL) setTypeface(typeface, style)
        setSingleLine()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
