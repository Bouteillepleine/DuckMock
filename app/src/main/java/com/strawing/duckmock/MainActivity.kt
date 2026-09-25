package com.strawing.duckmock

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.strawing.duckmock.common.Bridge
import com.strawing.duckmock.common.Config
import com.strawing.duckmock.common.MockConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.android.material.R as MR

class MainActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var divider: View
    private lateinit var nav: BottomNavigationView
    private lateinit var scroll: ScrollView

    private var config = MockConfig()
    private var snapshot: Root.Snapshot? = null
    private var live: Bundle? = null
    private var records: List<Bundle> = emptyList()
    private var loaded = false
    private var tab = R.id.tab_status

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val mode = data.getStringExtra(AppPickerActivity.EXTRA_MODE)
        val picked = data.getStringArrayListExtra(AppPickerActivity.EXTRA_SELECTED) ?: return@registerForActivityResult
        when (mode) {
            AppPickerActivity.MODE_SPOOFERS -> config.spoofers = LinkedHashSet(picked)
            AppPickerActivity.MODE_EXEMPT -> config.exempt = LinkedHashSet(picked)
        }
        persist()
        render()
    }

    private val cOnSurface get() = attr(MR.attr.colorOnSurface)
    private val cOnSurfaceVar get() = attr(MR.attr.colorOnSurfaceVariant)
    private val cCard get() = attr(MR.attr.colorSurfaceContainer, attr(MR.attr.colorSurface))
    private val cOutline get() = attr(MR.attr.colorOutlineVariant)
    private val cPrimary get() = attr(MR.attr.colorPrimary)
    private val cPrimaryCont get() = attr(MR.attr.colorPrimaryContainer)
    private val cOnPrimaryCont get() = attr(MR.attr.colorOnPrimaryContainer)
    private val cTertiaryCont get() = attr(MR.attr.colorTertiaryContainer, cPrimaryCont)
    private val cOnTertiaryCont get() = attr(MR.attr.colorOnTertiaryContainer, cOnPrimaryCont)
    private val cErrorCont get() = attr(MR.attr.colorErrorContainer)
    private val cOnErrorCont get() = attr(MR.attr.colorOnErrorContainer)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            )
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(cOutline)
            alpha = 0f
        }
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            divider.alpha = if (y > dp(4)) 1f else 0f
        }
        nav = BottomNavigationView(this).apply {
            inflateMenu(R.menu.bottom_nav)
            selectedItemId = tab
            setOnItemSelectedListener { item ->
                tab = item.itemId
                render()
                scroll.scrollTo(0, 0)
                divider.alpha = 0f
                true
            }
        }
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(divider)
            addView(scroll)
            addView(nav)
        }
        setContentView(shell)
        ViewCompat.setOnApplyWindowInsetsListener(shell) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        reload()
    }

    private fun reload() {
        Thread {
            val snap = runCatching { Root.snapshot() }.getOrNull()
            val state = runCatching { ServiceClient.state(this) }.getOrNull()
            val recs = runCatching { ServiceClient.records(this) }.getOrDefault(emptyList())
            runOnUiThread {
                snapshot = snap
                live = state
                records = recs
                snap?.config?.let { config = it }
                loaded = true
                render()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun persist() {
        val copy = config.copy(
            spoofers = LinkedHashSet(config.spoofers),
            exempt = LinkedHashSet(config.exempt),
        )
        Thread {
            val written = runCatching { Root.writeConfig(copy) }.getOrDefault(false)
            val pushed = runCatching { ServiceClient.push(this, copy) }.getOrDefault(false)
            runCatching { Root.refreshDescription() }
            runOnUiThread {
                if (!written) {
                    toast("Could not write the configuration, is root granted?")
                } else if (!pushed) {
                    toast("Saved. It takes effect on the next reboot.")
                }
                reload()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun render() {
        header.removeAllViews()
        content.removeAllViews()
        renderHeader()
        when (tab) {
            R.id.tab_hiding -> renderHiding()
            R.id.tab_apps -> renderApps()
            R.id.tab_log -> renderLog()
            else -> renderStatus()
        }
    }

    // ---------------------------------------------------------------- header

    private fun renderHeader() {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, dp(12))
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titles.addView(text("DuckMock", 26f, cOnSurface, Typeface.BOLD))
        titles.addView(text(headerSubtitle(), 13f, cOnSurfaceVar))
        bar.addView(titles)

        val mode = Theming.current(this)
        bar.addView(MaterialButton(this, null, MR.attr.materialIconButtonStyle).apply {
            text = mode.icon
            contentDescription = mode.label
            setOnClickListener {
                Theming.apply(this@MainActivity, mode.next())
                recreate()
            }
        })
        header.addView(bar)
    }

    private fun headerSubtitle(): String {
        val snap = snapshot ?: return if (loaded) "no root" else "reading the device…"
        if (!snap.rootAvailable) return "no root"
        if (!snap.moduleInstalled) return "module not installed"
        val version = snap.moduleVersion ?: Config.MODULE_VERSION
        return "v$version · ${snap.zygisk}"
    }

    // ---------------------------------------------------------------- status

    private fun renderStatus() {
        val snap = snapshot
        if (!loaded) {
            content.addView(banner("Reading…", "Asking the device what it is doing.", cCard, cOnSurfaceVar))
            return
        }
        if (snap == null || !snap.rootAvailable) {
            content.addView(
                banner(
                    "No root",
                    "DuckMock needs root to read and write its own configuration. Grant it, then reopen.",
                    cErrorCont,
                    cOnErrorCont,
                )
            )
            return
        }
        if (!snap.moduleInstalled) {
            content.addView(
                banner(
                    "Module not installed",
                    "Flash the DuckMock zip in KernelSU, Magisk or APatch, then reboot.",
                    cErrorCont,
                    cOnErrorCont,
                )
            )
            return
        }

        content.addView(verdictBanner(snap))

        card("What system_server is doing") {
            val state = live
            if (state == null) {
                addView(text("The module is not answering.", 14f, cOnSurfaceVar))
                addView(
                    text(
                        "That is normal until the next reboot after a fresh install. It also happens when every hook is switched off.",
                        12f,
                        cOnSurfaceVar,
                    )
                )
            } else {
                addView(infoRow("Location marker", "${state.getInt(Bridge.STATE_LOCATION_HOOKS)} hooks"))
                addView(infoRow("Settings provider", "${state.getInt(Bridge.STATE_SETTINGS_HOOKS)} hooks"))
                addView(infoRow("App-ops gate", "${state.getInt(Bridge.STATE_APPOPS_HOOKS)} hooks"))
                addView(infoRow("Mock-location op", "#${state.getInt(Bridge.STATE_OP_CODE)}"))
                addView(infoRow("Counters", state.getString(Bridge.STATE_CLEARED) ?: "—"))
                addView(infoRow("Apps touched", "${state.getInt(Bridge.STATE_TOUCHED_APPS)}"))
            }
        }

        card("What the system still says") {
            addView(
                infoRow(
                    "Secure ${Config.MOCK_LOCATION_KEY}",
                    snap.mockLocationSetting ?: "unset",
                )
            )
            val holders = snap.mockOpHolders
            addView(
                infoRow(
                    "Holds the mock-location op",
                    if (holders.isEmpty()) "nothing" else holders.joinToString(", "),
                )
            )
            addView(
                text(
                    "These two lines are read as root, so they always show the truth. An app that DuckMock lies to sees the mock-location op held by nothing.",
                    12f,
                    cOnSurfaceVar,
                )
            )
        }

        card("Kill switch") {
            addView(
                switchRow(
                    "Disable every hook",
                    "Survives a reboot. Use it if a change makes the phone misbehave.",
                    snap.hooksKilled,
                ) { checked ->
                    Thread {
                        Root.setHooksKilled(checked)
                        runOnUiThread { reload() }
                    }.apply { isDaemon = true }.start()
                }
            )
            addView(
                text(
                    "The module also disables itself on its own after three failed boots.",
                    12f,
                    cOnSurfaceVar,
                )
            )
        }
    }

    private fun verdictBanner(snap: Root.Snapshot): View {
        if (snap.hooksKilled) {
            return banner(
                "Hooks disabled",
                "The kill switch is on. Every app reads the truth.",
                cErrorCont,
                cOnErrorCont,
            )
        }
        if (config.paused) {
            return banner(
                "Paused",
                "The hooks are loaded but every app reads the truth.",
                cTertiaryCont,
                cOnTertiaryCont,
            )
        }
        val parts = ArrayList<String>()
        if (config.hideLocationFlag) parts.add("mock flag")
        if (config.hideAppOps) parts.add("app-ops")
        if (config.hideSettingsKey) parts.add("settings key")
        if (parts.isEmpty()) {
            return banner(
                "Nothing enabled",
                "Every switch on the Hiding tab is off.",
                cTertiaryCont,
                cOnTertiaryCont,
            )
        }
        val state = live
        val detail = buildString {
            append("Hiding ")
            append(parts.joinToString(", "))
            append(" from every app.")
            if (state != null && state.getInt(Bridge.STATE_LOCATION_HOOKS) > 0) {
                append(" Self-test: ")
                append(state.getString(Bridge.STATE_CLEARED)?.substringBefore(" ·") ?: "unknown")
                append('.')
            }
        }
        return banner("Active", detail, cPrimaryCont, cOnPrimaryCont)
    }

    // ---------------------------------------------------------------- hiding

    private fun renderHiding() {
        card("Location objects") {
            addView(
                switchRow(
                    "Clear the mock marker",
                    "Stops system_server marking a location as mock before it reaches any app. This is the hook that matters.",
                    config.hideLocationFlag,
                ) { config.hideLocationFlag = it; persist(); render() }
            )
            addView(
                switchRow(
                    "Rename odd providers",
                    "A mock provider called anything other than gps, network, passive or fused is reported as gps.",
                    config.normalizeProvider,
                    enabled = config.hideLocationFlag,
                ) { config.normalizeProvider = it; persist() }
            )
        }

        card("App-ops") {
            addView(
                switchRow(
                    "Hide the mock-location op",
                    "An app asking who holds the op is told nobody does. The system and your spoofer keep the truth.",
                    config.hideAppOps,
                ) { config.hideAppOps = it; persist(); render() }
            )
            addView(
                switchRow(
                    "Grant the op without the picker",
                    "Your spoofer works while the developer-options picker stays empty. Pick the spoofer on the Apps tab first.",
                    config.grantMockOp,
                    enabled = config.spoofers.isNotEmpty(),
                ) { config.grantMockOp = it; persist(); render() }
            )
            if (config.spoofers.isEmpty()) {
                addView(
                    text(
                        "No spoofer chosen yet, so granting is switched off.",
                        12f,
                        cOnSurfaceVar,
                    )
                )
            }
        }

        card("Settings") {
            addView(
                switchRow(
                    "Hide the legacy settings key",
                    "Secure.${Config.MOCK_LOCATION_KEY} reads 0. Only old detectors still look here.",
                    config.hideSettingsKey,
                ) { config.hideSettingsKey = it; persist(); render() }
            )
            addView(
                switchRow(
                    "Cover the cursor path too",
                    "Also rewrites the key when an app queries the settings provider instead of calling it.",
                    config.coverQueryPath,
                    enabled = config.hideSettingsKey,
                ) { config.coverQueryPath = it; persist() }
            )
        }

        card("Module") {
            addView(
                switchRow(
                    "Pause",
                    "Keeps the hooks loaded but lets every app read the truth. No reboot needed.",
                    config.paused,
                ) { config.paused = it; persist(); render() }
            )
            addView(
                switchRow(
                    "Verbose log",
                    "Writes a logcat line for every interception under the DuckMock tag.",
                    config.verboseLog,
                ) { config.verboseLog = it; persist() }
            )
        }

        content.addView(
            text(
                "Turning a hook on for the first time needs a reboot: hooks are installed once, shortly after boot. Turning one off takes effect immediately.",
                12f,
                cOnSurfaceVar,
            ).apply { setPadding(dp(4), dp(4), dp(4), 0) }
        )
    }

    // ------------------------------------------------------------------ apps

    private fun renderApps() {
        card("Your spoofer") {
            addView(
                text(
                    "The app that sets the fake position. It always reads the truth, and it is the app that can be granted the op without the picker.",
                    13f,
                    cOnSurfaceVar,
                )
            )
            addView(packageList(config.spoofers, "No spoofer chosen"))
            addView(
                MaterialButton(this@MainActivity, null, MR.attr.materialButtonOutlinedStyle).apply {
                    text = "Choose"
                    setOnClickListener { openPicker(AppPickerActivity.MODE_SPOOFERS, config.spoofers) }
                }
            )
        }

        card("Always tell the truth") {
            addView(
                text(
                    "Apps that should keep seeing the real mock state. Add anything that misbehaves when it is lied to.",
                    13f,
                    cOnSurfaceVar,
                )
            )
            addView(packageList(config.exempt, "Nothing added"))
            addView(
                MaterialButton(this@MainActivity, null, MR.attr.materialButtonOutlinedStyle).apply {
                    text = "Choose"
                    setOnClickListener { openPicker(AppPickerActivity.MODE_EXEMPT, config.exempt) }
                }
            )
        }

        card("Always spared") {
            addView(
                text(
                    "These are never lied to, so you keep control of the phone and of this app.",
                    13f,
                    cOnSurfaceVar,
                )
            )
            for (pkg in Config.SPARE_PACKAGES.sorted()) {
                addView(text(pkg, 13f, cOnSurface))
            }
        }
    }

    private fun openPicker(mode: String, selected: Set<String>) {
        val intent = Intent(this, AppPickerActivity::class.java).apply {
            putExtra(AppPickerActivity.EXTRA_MODE, mode)
            putStringArrayListExtra(AppPickerActivity.EXTRA_SELECTED, ArrayList(selected))
        }
        picker.launch(intent)
    }

    private fun packageList(packages: Set<String>, empty: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        if (packages.isEmpty()) {
            box.addView(text(empty, 13f, cOnSurfaceVar, Typeface.ITALIC))
            return box
        }
        for (pkg in packages) box.addView(text(pkg, 14f, cOnSurface))
        return box
    }

    // ------------------------------------------------------------------- log

    private fun renderLog() {
        if (records.isEmpty()) {
            card("Nothing recorded") {
                addView(
                    text(
                        "The module notes each app it lied to, and each location it cleaned. Nothing yet on this boot.",
                        13f,
                        cOnSurfaceVar,
                    )
                )
            }
            return
        }
        card("Interceptions this boot") {
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            for (rec in records.sortedByDescending { it.getLong(Bridge.REC_LAST) }) {
                val uid = rec.getInt(Bridge.REC_UID)
                val count = rec.getInt(Bridge.REC_COUNT)
                val last = rec.getLong(Bridge.REC_LAST)
                val kinds = rec.getStringArrayList(Bridge.REC_KINDS)?.joinToString(", ") ?: ""
                val who = if (uid == Config.SYSTEM_UID) "system_server" else "uid $uid"
                addView(infoRow(who, "$count × $kinds · ${fmt.format(Date(last))}"))
            }
        }
        content.addView(
            MaterialButton(this, null, MR.attr.materialButtonOutlinedStyle).apply {
                text = "Clear"
                setOnClickListener {
                    Thread {
                        ServiceClient.clearRecords(this@MainActivity)
                        runOnUiThread { reload() }
                    }.apply { isDaemon = true }.start()
                }
            }
        )
    }

    // -------------------------------------------------------------- builders

    private fun card(title: String, build: LinearLayout.() -> Unit) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        body.addView(text(title, 15f, cPrimary, Typeface.BOLD).apply {
            setPadding(0, 0, 0, dp(6))
        })
        body.build()
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(cCard)
            radius = dp(20).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
            addView(body)
        }
        content.addView(card)
    }

    private fun banner(title: String, detail: String, bg: Int, fg: Int): View {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        body.addView(text(title, 20f, fg, Typeface.BOLD))
        body.addView(text(detail, 13f, fg).apply { alpha = 0.85f })
        return MaterialCardView(this).apply {
            setCardBackgroundColor(bg)
            radius = dp(24).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
            addView(body)
        }
    }

    private fun infoRow(label: String, value: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(5), 0, dp(5))
        }
        row.addView(text(label, 14f, cOnSurfaceVar).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(text(value, 14f, cOnSurface).apply {
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
        })
        return row
    }

    private fun switchRow(
        label: String,
        sub: String,
        checked: Boolean,
        enabled: Boolean = true,
        onChange: (Boolean) -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            alpha = if (enabled) 1f else 0.45f
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(text(label, 15f, cOnSurface))
        texts.addView(text(sub, 12f, cOnSurfaceVar))
        row.addView(texts)
        row.addView(MaterialSwitch(this).apply {
            isChecked = checked
            isEnabled = enabled
            setOnCheckedChangeListener { view, value ->
                if (!view.isPressed && !view.isFocused) return@setOnCheckedChangeListener
                onChange(value)
            }
        })
        return row
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
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun attr(id: Int, fallback: Int = 0): Int =
        MaterialColors.getColor(this, id, fallback)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
