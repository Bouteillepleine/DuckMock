package com.strawing.duckmock

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
                if (!loaded) spoof = SpoofPrefs.target(this)
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
                when {
                    pushed && written -> Unit
                    pushed -> toast("Applied now. Grant root to keep it after a reboot.")
                    written -> toast("Saved. It takes effect on the next reboot.")
                    else -> toast("Nothing saved: no root, and the module is not answering.")
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
            R.id.tab_spoof -> renderSpoof()
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
        if (!loaded) return "reading the device…"
        val armedHooks = armed()?.get("location")?.toIntOrNull() ?: 0
        val where = when {
            live != null -> "running in system_server"
            armedHooks > 0 -> "armed in system_server"
            snapshot?.moduleInstalled == true -> "installed, not armed"
            snapshot?.rootAvailable == true -> "not installed"
            else -> "no live module"
        }
        val module = snapshot?.moduleVersion
        return if (module != null) "v$module · $where" else "manager v${managerVersion()} · $where"
    }

    private fun managerVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull() ?: "?"


    private val uidNames = HashMap<Int, String>()
    private val uidPackages = HashMap<Int, String>()

    private fun resolveUid(uid: Int) {
        if (uidNames.containsKey(uid)) return
        if (uid == Config.SYSTEM_UID) {
            uidNames[uid] = "Android system"
            uidPackages[uid] = "system_server · uid $uid"
            return
        }
        val pm = packageManager
        val packages = runCatching { pm.getPackagesForUid(uid) }.getOrNull()
        if (packages.isNullOrEmpty()) {
            uidNames[uid] = "Unknown app"
            uidPackages[uid] = "uid $uid"
            return
        }
        val best = packages.firstOrNull { pkg ->
            runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() != null
        } ?: packages[0]
        val label = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(best, 0)).toString()
        }.getOrNull()
        uidNames[uid] = if (label.isNullOrBlank()) best else label
        uidPackages[uid] = if (packages.size > 1) {
            "$best +${packages.size - 1} more · uid $uid"
        } else {
            "$best · uid $uid"
        }
    }

    private fun appLabel(uid: Int): String {
        resolveUid(uid)
        return uidNames[uid] ?: "uid $uid"
    }

    private fun appPackage(uid: Int): String {
        resolveUid(uid)
        return uidPackages[uid] ?: "uid $uid"
    }

    // ---------------------------------------------------------------- status

    private fun renderStatus() {
        if (!loaded) {
            content.addView(banner("Reading…", "Asking the module what it is doing.", cCard, cOnSurfaceVar))
            return
        }

        content.addView(verdictBanner())

        val state = live
        if (state != null) {
            card("Since this boot") {
                val counters = (state.getString(Bridge.STATE_CLEARED) ?: "").split(" · ")
                val tiles = ArrayList<Pair<String, String>>()
                for (part in counters) {
                    val trimmed = part.trim()
                    if (trimmed.isEmpty()) continue
                    val count = trimmed.substringBefore(' ')
                    val what = trimmed.substringAfter(' ', "")
                    if (what.isNotEmpty()) tiles.add(count to what)
                }
                tiles.add("${state.getInt(Bridge.STATE_TOUCHED_APPS)}" to "apps")
                addView(statTiles(tiles))
            }

            card("Live in system_server") {
                addView(infoRow("Mock marker", hookLine(state.getInt(Bridge.STATE_LOCATION_HOOKS))))
                addView(infoRow("App-ops gate", hookLine(state.getInt(Bridge.STATE_APPOPS_HOOKS))))
                addView(
                    infoRow(
                        "Settings provider",
                        settingsLine(
                            state.getInt(Bridge.STATE_SETTINGS_HOOKS),
                            state.getBoolean(Bridge.STATE_SETTINGS_LENT),
                        ),
                    )
                )
                addView(infoRow("Self-test", state.getString(Bridge.STATE_CLEARED)?.substringBefore(" ·") ?: "—"))
                addView(infoRow("Mock-location op", "#${state.getInt(Bridge.STATE_OP_CODE)}"))
                addView(
                    infoRow(
                        "Hook engine",
                        when (state.getString(Bridge.STATE_ENGINE)) {
                            "own" -> "own"
                            "adopted" -> "borrowed"
                            else -> "not started"
                        },
                    )
                )
                state.getString(Bridge.STATE_GNSS)?.takeIf { it.isNotBlank() }?.let {
                    addView(infoRow("Satellites", it))
                }
            }
        } else {
            if (snapshot?.armReport?.contains("refused:") == true) {
                card("Stopped for safety") {
                    addView(
                        text(
                            "Three arms in a row did not stay up, so nothing was installed.",
                            13f,
                            cOnSurfaceVar,
                        )
                    )
                }
            } else {
                val report = armed()
                if (report != null) {
                    card("Reported when it armed") {
                        addView(statTiles(listOf(
                            (report["location"] ?: "0") to "location",
                            (report["appops"] ?: "0") to "app-ops",
                            (report["settings"] ?: "0") to "settings",
                        )))
                        addView(infoRow("Self-test", report["verdict"] ?: "unknown"))
                        addView(
                            infoRow(
                                "Hook engine",
                                when (report["engine"]) {
                                    "own" -> "own"
                                    "adopted" -> "borrowed"
                                    else -> "not started"
                                },
                            )
                        )
                        addView(infoRow("Armed at", report["stamp"] ?: "—"))
                    }
                    if ((report["settings"]?.toIntOrNull() ?: 0) == 0) {
                        val lent = report["lent"] == "true"
                        card(if (lent) "Riding a neighbour" else "Settings key visible") {
                            addView(
                                text(
                                    if (lent) {
                                        "Another module holds the settings provider, so the mock-location key is hidden through its hook instead of ours. Everything else is armed normally."
                                    } else {
                                        "Another module holds the settings provider and would not take our filter, so the mock-location key is NOT hidden. Every other kind of hiding is unaffected."
                                    },
                                    13f,
                                    cOnSurfaceVar,
                                )
                            )
                        }
                    }
                } else {
                    card("Not answering") {
                        addView(text("Nothing is hiding anything. Either it has not been flashed, the phone has not rebooted since, it is still arming, or every switch is off.", 13f, cOnSurfaceVar))
                    }
                }
            }
        }

        val snap = snapshot
        if (snap?.rootAvailable == true) {
            card("What the system really holds") {
                addView(infoRow("Secure ${Config.MOCK_LOCATION_KEY}", snap.mockLocationSetting ?: "unset"))
                val holders = snap.mockOpHolders
                addView(
                    infoRow(
                        "Mock-location op",
                        if (holders.isEmpty()) "held by nothing" else holders.joinToString(", "),
                    )
                )
                addView(infoRow("Module on disk", if (snap.moduleInstalled) "installed" else "missing"))
                addView(
                    text(
                        "Read as root, so this is the truth. A lied-to app sees the op held by nothing.",
                        12f,
                        cOnSurfaceVar,
                    ).apply { setPadding(0, dp(8), 0, 0) }
                )
            }
            card("Kill switch") {
                addView(
                    switchRow(
                        "Disable every hook",
                        "Survives a reboot. Turning it off also clears the safety counter.",
                        snap.hooksKilled,
                    ) { checked ->
                        Thread {
                            Root.setHooksKilled(checked)
                            runOnUiThread { reload() }
                        }.apply { isDaemon = true }.start()
                    }
                )
                if (snap.armReport?.contains("refused:") == true) {
                    addView(
                        MaterialButton(this@MainActivity, null, MR.attr.materialButtonOutlinedStyle).apply {
                            text = "Let it arm again"
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply { topMargin = dp(8) }
                            setOnClickListener {
                                Thread {
                                    Root.resetSafetyCounter()
                                    runOnUiThread {
                                        toast("Cleared. It will arm on the next reboot.")
                                        reload()
                                    }
                                }.apply { isDaemon = true }.start()
                            }
                        }
                    )
                }
            }
        } else {
            card("Root not granted") {
                addView(
                    text(
                        "Everything above still works without it. Root is only needed to keep settings across a reboot and to use the kill switch.",
                        13f,
                        cOnSurfaceVar,
                    )
                )
                addView(
                    MaterialButton(this@MainActivity, null, MR.attr.materialButtonOutlinedStyle).apply {
                        text = "Check again"
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(8) }
                        setOnClickListener { reload() }
                    }
                )
            }
        }
    }

    private fun statTiles(items: List<Pair<String, String>>): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(2))
        }
        for ((value, label) in items) {
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dp(2), dp(6), dp(2), dp(6))
            }
            tile.addView(
                text(value, 24f, cPrimary, Typeface.BOLD).apply { gravity = Gravity.CENTER }
            )
            tile.addView(text(label, 11f, cOnSurfaceVar).apply { gravity = Gravity.CENTER })
            row.addView(tile)
        }
        return row
    }

    private fun hookLine(count: Int): String =
        if (count == 0) "not armed" else "$count hook${if (count == 1) "" else "s"}"

    private fun settingsLine(count: Int, lent: Boolean): String = when {
        count > 0 -> hookLine(count)
        lent -> "served by a neighbour"
        else -> "not armed"
    }

    private fun armed(): Map<String, String>? {
        val line = snapshot?.armReport?.lineSequence()?.lastOrNull { it.contains("armed:") } ?: return null
        val out = HashMap<String, String>()
        out["stamp"] = line.substringBefore("  armed:").trim()
        Regex("(\\w+)=([^\\s]+)").findAll(line.substringAfter("armed:")).forEach {
            out[it.groupValues[1]] = it.groupValues[2]
        }
        Regex("\\(([a-z]+)\\)").find(line)?.let { out["verdict"] = it.groupValues[1] }
        return out.takeIf { it.containsKey("location") }
    }

    private fun verdictBanner(): View {
        val snap = snapshot
        val state = live
        if (snap?.rootAvailable == true && snap.hooksKilled) {
            return banner(
                "Hooks disabled",
                "The kill switch is on. Every app reads the truth.",
                cErrorCont,
                cOnErrorCont,
            )
        }
        if (state == null) {
            if (snapshot?.armReport?.contains("refused:") == true) {
                return banner(
                    "Stopped itself",
                    "Three arms in a row did not survive, so the module refused to install anything. Clear the kill switch below to let it try again.",
                    cErrorCont,
                    cOnErrorCont,
                )
            }
            val report = armed()
            val locationHooks = report?.get("location")?.toIntOrNull() ?: 0
            if (locationHooks > 0) {
                return banner(
                    "Active",
                    "Hiding the mock flag from every app. The live view below is unavailable, but the module is armed.",
                    cPrimaryCont,
                    cOnPrimaryCont,
                )
            }
            return banner(
                "Not running",
                "system_server is not answering, so nothing is being hidden.",
                cTertiaryCont,
                cOnTertiaryCont,
            )
        }
        if (state.getBoolean(Bridge.STATE_PAUSED)) {
            return banner(
                "Paused",
                "The hooks are loaded but every app reads the truth.",
                cTertiaryCont,
                cOnTertiaryCont,
            )
        }
        val parts = ArrayList<String>()
        if (state.getBoolean(Bridge.STATE_HIDE_LOCATION_FLAG) &&
            state.getInt(Bridge.STATE_LOCATION_HOOKS) > 0
        ) parts.add("mock flag")
        if (state.getBoolean(Bridge.STATE_HIDE_APP_OPS) &&
            state.getInt(Bridge.STATE_APPOPS_HOOKS) > 0
        ) parts.add("app-ops")
        if (state.getBoolean(Bridge.STATE_HIDE_SETTINGS_KEY) &&
            (state.getInt(Bridge.STATE_SETTINGS_HOOKS) > 0 ||
                state.getBoolean(Bridge.STATE_SETTINGS_LENT))
        ) parts.add("settings key")
        if (parts.isEmpty()) {
            return banner(
                "Nothing armed",
                "Every switch is off, or no hook could be installed.",
                cTertiaryCont,
                cOnTertiaryCont,
            )
        }
        val selfTest = state.getString(Bridge.STATE_CLEARED)?.substringBefore(" ·")
        val detail = buildString {
            append("Hiding ")
            append(parts.joinToString(", "))
            append(" from every app.")
            if (selfTest == "live") append(" Self-test passed.")
            else if (selfTest != null) append(" Self-test: $selfTest.")
        }
        return banner("Active", detail, cPrimaryCont, cOnPrimaryCont)
    }

    // -------------------------------------------------------------- position

    private var spoof = SpoofTarget()
    private var geoQuery = ""
    private var geoResults: List<android.location.Address> = emptyList()
    private var geoNote: String? = null

    private fun searchAddress() {
        geoNote = "Searching…"
        geoResults = emptyList()
        render()
        val query = geoQuery
        Geo.search(this, query) { hits, error ->
            runOnUiThread {
                geoResults = hits
                geoNote = error
                render()
            }
        }
    }

    private fun describeHere() {
        geoNote = "Looking up…"
        render()
        Geo.describe(this, spoof.latitude, spoof.longitude) { line ->
            runOnUiThread {
                geoNote = line?.let { "That is $it" } ?: "No address for those coordinates."
                render()
            }
        }
    }

    private fun renderSpoof() {
        val running = SpoofPrefs.running(this)
        content.addView(
            if (running) {
                banner(
                    "Spoofing",
                    "Every app is told you are at ${spoof.label.ifBlank { spoof.pretty() }}.",
                    cPrimaryCont,
                    cOnPrimaryCont,
                )
            } else {
                banner(
                    "Not spoofing",
                    "Your real position is being reported. Start to appear at " +
                        spoof.label.ifBlank { spoof.pretty() } + ".",
                    cCard,
                    cOnSurfaceVar,
                )
            }
        )

        content.addView(
            MaterialButton(this).apply {
                text = if (running) "Stop" else "Start"
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) }
                setOnClickListener { if (running) stopSpoof() else startSpoof() }
            }
        )

        card("Find an address") {
            addView(
                valueField(
                    "Address, place or postcode",
                    geoQuery,
                    imeSearch = true,
                    onSearch = { searchAddress() },
                ) { geoQuery = it }
            )
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            row.addView(
                MaterialButton(this@MainActivity).apply {
                    text = "Search"
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                    ).apply { marginEnd = dp(6) }
                    setOnClickListener { searchAddress() }
                }
            )
            row.addView(
                MaterialButton(this@MainActivity, null, MR.attr.materialButtonOutlinedStyle).apply {
                    text = "What is here?"
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                    ).apply { marginStart = dp(6) }
                    setOnClickListener { describeHere() }
                }
            )
            addView(row)

            geoNote?.let {
                addView(text(it, 13f, cPrimary).apply { setPadding(0, dp(10), 0, 0) })
            }

            if (geoResults.isNotEmpty()) {
                addView(
                    text(
                        "${geoResults.size} result${if (geoResults.size == 1) "" else "s"} · tap one to go there",
                        12f,
                        cOnSurfaceVar,
                    ).apply { setPadding(0, dp(10), 0, dp(2)) }
                )
                for (hit in geoResults) {
                    val line = Geo.label(hit)
                    addView(
                        resultRow(line, "%.5f, %.5f".format(java.util.Locale.ROOT, hit.latitude, hit.longitude)) {
                            spoof = spoof.copy(
                                latitude = hit.latitude,
                                longitude = hit.longitude,
                                label = line.take(48),
                            )
                            SpoofPrefs.setTarget(this@MainActivity, spoof)
                            retarget()
                            geoResults = emptyList()
                            geoNote = "Target set to $line"
                            hideKeyboard()
                            render()
                        }
                    )
                }
            }

            if (!Geo.available()) {
                addView(
                    text("No geocoder here, so only coordinates will work.", 12f, cOnSurfaceVar)
                )
            }
        }

        card("Where to be") {
            addView(
                sideBySide(
                    valueField("Latitude", spoof.latitude.toString(), numeric = true) {
                        decimal(it)?.toDoubleOrNull()?.let { v -> spoof = spoof.copy(latitude = v) }
                    },
                    valueField("Longitude", spoof.longitude.toString(), numeric = true) {
                        decimal(it)?.toDoubleOrNull()?.let { v -> spoof = spoof.copy(longitude = v) }
                    },
                )
            )
            addView(
                sideBySide(
                    valueField("Altitude (m)", spoof.altitude.toString(), numeric = true) {
                        decimal(it)?.toDoubleOrNull()?.let { v -> spoof = spoof.copy(altitude = v) }
                    },
                    valueField("Accuracy (m)", spoof.accuracy.toString(), numeric = true) {
                        decimal(it)?.toFloatOrNull()?.let { v -> spoof = spoof.copy(accuracy = v) }
                    },
                )
            )
            addView(
                sideBySide(
                    valueField("Wander (m)", spoof.jitterMetres.toString(), numeric = true) {
                        decimal(it)?.toFloatOrNull()?.let { v -> spoof = spoof.copy(jitterMetres = v) }
                    },
                    valueField("Name", spoof.label) { spoof = spoof.copy(label = it) },
                )
            )
            addView(
                text(
                    "Wander drifts the position a few metres on each update. A fix frozen to the centimetre is a tell; set 0 to stand still.",
                    12f,
                    cOnSurfaceVar,
                ).apply { setPadding(0, dp(10), 0, 0) }
            )
            val actions = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            actions.addView(
                MaterialButton(this@MainActivity, null, MR.attr.materialButtonOutlinedStyle).apply {
                    text = "Use my position"
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                    ).apply { marginEnd = dp(6) }
                    setOnClickListener { fillFromReal() }
                }
            )
            actions.addView(
                MaterialButton(this@MainActivity, null, MR.attr.materialButtonOutlinedStyle).apply {
                    text = "Save favourite"
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                    ).apply { marginStart = dp(6) }
                    setOnClickListener {
                        if (spoof.label.isBlank()) {
                            toast("Give it a name first.")
                        } else {
                            SpoofPrefs.addFavourite(this@MainActivity, spoof)
                            render()
                        }
                    }
                }
            )
            addView(actions)
        }

        val favourites = SpoofPrefs.favourites(this)
        if (favourites.isNotEmpty()) {
            card("Favourites") {
                for (fav in favourites) {
                    val row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(6), 0, dp(6))
                    }
                    val labels = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams =
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    labels.addView(text(fav.label, 15f, cOnSurface))
                    labels.addView(text(fav.pretty(), 12f, cOnSurfaceVar))
                    labels.setOnClickListener {
                        spoof = fav
                        SpoofPrefs.setTarget(this@MainActivity, fav)
                        render()
                    }
                    row.addView(labels)
                    row.addView(
                        MaterialButton(
                            this@MainActivity,
                            null,
                            MR.attr.materialButtonOutlinedStyle,
                        ).apply {
                            text = "Remove"
                            setOnClickListener {
                                SpoofPrefs.removeFavourite(this@MainActivity, fav.label)
                                render()
                            }
                        }
                    )
                    addView(row)
                }
            }
        }

        card("Nearby networks") {
            addView(
                switchRow(
                    "Withhold Wi-Fi identity",
                    "While spoofing, apps get no scan list and a blanked access point. The BSSID of the router you are on pins your real position in public databases.",
                    config.hideWifi,
                ) { config.hideWifi = it; persist(); render() }
            )
            live?.getString(Bridge.STATE_WIFI)?.let { addView(infoRow("Engine", it)) }
            addView(
                text(
                    "The values returned are the ones Android itself gives an app without location permission, so they are unremarkable rather than obviously fake.",
                    12f,
                    cOnSurfaceVar,
                )
            )
        }

        card("Satellites") {
            addView(
                switchRow(
                    "Synthesise a sky",
                    "While spoofing, publish a plausible satellite view and withhold the raw measurements that would contradict it.",
                    config.synthesiseGnss,
                ) { config.synthesiseGnss = it; persist(); render() }
            )
            live?.getString(Bridge.STATE_GNSS)?.let { addView(infoRow("Engine", it)) }
            addView(
                text(
                    "Plausible, not astronomical: counts, strengths and elevations hold up, but nothing is computed from ephemeris.",
                    12f,
                    cOnSurfaceVar,
                )
            )
        }

    }

    private fun startSpoof() {
        val missing = arrayOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        ).filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 7)
            return
        }
        SpoofPrefs.setTarget(this, spoof)
        runCatching { SpoofService.start(this) }
            .onFailure { toast("Could not start: ${it.message}") }
        window.decorView.postDelayed({ render(); reload() }, 1500)
    }

    private fun retarget() {
        if (!SpoofPrefs.running(this)) return
        Thread {
            runCatching {
                ServiceClient.setSpoofing(this, true, spoof.latitude, spoof.longitude)
            }
            SpoofService.retarget(this)
        }.apply { isDaemon = true }.start()
    }

    private fun stopSpoof() {
        SpoofService.stop(this)
        window.decorView.postDelayed({ render(); reload() }, 700)
    }

    private fun fillFromReal() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                8,
            )
            return
        }
        val lm = getSystemService(android.location.LocationManager::class.java)
        val real = SpoofService.PROVIDERS.plus("fused")
            .asSequence()
            .mapNotNull { runCatching { lm?.getLastKnownLocation(it) }.getOrNull() }
            .firstOrNull()
        if (real == null) {
            toast("No recent fix to copy. Open a map app first.")
            return
        }
        spoof = spoof.copy(
            latitude = real.latitude,
            longitude = real.longitude,
            altitude = real.altitude,
        )
        SpoofPrefs.setTarget(this, spoof)
        render()
    }

    private fun valueField(
        label: String,
        value: String,
        numeric: Boolean = false,
        imeSearch: Boolean = false,
        onSearch: (() -> Unit)? = null,
        onChange: (String) -> Unit,
    ): View {
        val themed = android.view.ContextThemeWrapper(
            this,
            MR.style.Widget_Material3_TextInputLayout_OutlinedBox,
        )
        val field = com.google.android.material.textfield.TextInputEditText(themed).apply {
            setText(value)
            setSingleLine()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            if (numeric) {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            }
            if (imeSearch) {
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                        onSearch?.invoke()
                        true
                    } else {
                        false
                    }
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = onChange(s?.toString().orEmpty())
            })
        }
        return com.google.android.material.textfield.TextInputLayout(themed).apply {
            hint = label
            isHintEnabled = true
            addView(field)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
    }

    private fun sideBySide(left: View, right: View): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        left.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = dp(6); topMargin = dp(8) }
        right.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = dp(6); topMargin = dp(8) }
        row.addView(left)
        row.addView(right)
        return row
    }

    private fun decimal(raw: String): String? =
        raw.trim().replace(',', '.').takeIf { it.isNotEmpty() && it != "-" && it != "." }

    private fun hideKeyboard() {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        runCatching { imm?.hideSoftInputFromWindow(window.decorView.windowToken, 0) }
    }

    private fun resultRow(title: String, sub: String, onPick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(14))
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackground, outValue, true,
            )
            setBackgroundResource(outValue.resourceId)
            isClickable = true
            setOnClickListener { onPick() }
        }
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_status)
            imageTintList = android.content.res.ColorStateList.valueOf(cPrimary)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(14) }
        })
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(text(title, 15f, cOnSurface))
        texts.addView(text(sub, 12f, cOnSurfaceVar))
        row.addView(texts)
        return row
    }

    // ---------------------------------------------------------------- hiding

    private fun renderHiding() {
        content.addView(
            text(
                "Turning a hook on for the first time needs a reboot. Turning one off is immediate.",
                12f,
                cOnSurfaceVar,
            ).apply { setPadding(dp(4), dp(12), dp(4), 0) }
        )

        card("Location") {
            addView(
                switchRow(
                    "Clear the mock marker",
                    "The hook that matters. No app sees the fix as mock.",
                    config.hideLocationFlag,
                ) { config.hideLocationFlag = it; persist(); render() }
            )
            addView(
                switchRow(
                    "Rename odd providers",
                    "Anything not gps, network, passive or fused is reported as gps.",
                    config.normalizeProvider,
                    enabled = config.hideLocationFlag,
                ) { config.normalizeProvider = it; persist() }
            )
        }

        card("App-ops") {
            addView(
                switchRow(
                    "Hide the mock-location op",
                    "Apps are told nobody holds it. The system and your spoofer keep the truth.",
                    config.hideAppOps,
                ) { config.hideAppOps = it; persist(); render() }
            )
            addView(
                switchRow(
                    "Grant it without the picker",
                    if (config.spoofers.isEmpty()) {
                        "Choose a spoofer on the Apps tab to enable this."
                    } else {
                        "Your spoofer works while developer options stay empty."
                    },
                    config.grantMockOp,
                    enabled = config.spoofers.isNotEmpty(),
                ) { config.grantMockOp = it; persist(); render() }
            )
        }

        card("Settings") {
            addView(
                switchRow(
                    "Hide the legacy key",
                    "Secure.mock_location reads 0. Only old detectors look here.",
                    config.hideSettingsKey,
                ) { config.hideSettingsKey = it; persist(); render() }
            )
            addView(
                switchRow(
                    "Cover the cursor path",
                    "Also rewrites it when an app queries the provider instead of calling it.",
                    config.coverQueryPath,
                    enabled = config.hideSettingsKey,
                ) { config.coverQueryPath = it; persist() }
            )
        }

        card("Module") {
            addView(
                switchRow(
                    "Pause",
                    "Hooks stay loaded, every app reads the truth. No reboot.",
                    config.paused,
                ) { config.paused = it; persist(); render() }
            )
            addView(
                switchRow(
                    "Verbose log",
                    "One logcat line per interception, under the DuckMock tag.",
                    config.verboseLog,
                ) { config.verboseLog = it; persist() }
            )
        }
    }

    // ------------------------------------------------------------------ apps

    private fun renderApps() {
        renderRefused()
        card("Your spoofer") {
            addView(
                text(
                    "The app that sets the fake position. It always reads the truth, and it is the one that can be granted the op without the picker.",
                    13f,
                    cOnSurfaceVar,
                )
            )
            if (config.spoofers.isEmpty()) {
                addView(emptyHint("None chosen — DuckMock can spoof on its own from the Position tab."))
            } else {
                for (pkg in config.spoofers) {
                    addView(appRow(pkg) {
                        config.spoofers.remove(pkg)
                        if (config.spoofers.isEmpty()) config.grantMockOp = false
                        persist()
                        render()
                    })
                }
            }
            addView(
                MaterialButton(this@MainActivity, null, MR.attr.materialButtonOutlinedStyle).apply {
                    text = if (config.spoofers.isEmpty()) "Choose" else "Change"
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(8) }
                    setOnClickListener {
                        openPicker(AppPickerActivity.MODE_SPOOFERS, config.spoofers)
                    }
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
            if (config.exempt.isEmpty()) {
                addView(emptyHint("Nothing added."))
            } else {
                for (pkg in config.exempt) {
                    addView(appRow(pkg) {
                        config.exempt.remove(pkg)
                        persist()
                        render()
                    })
                }
            }
            addView(
                MaterialButton(this@MainActivity, null, MR.attr.materialButtonOutlinedStyle).apply {
                    text = if (config.exempt.isEmpty()) "Choose" else "Change"
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(8) }
                    setOnClickListener {
                        openPicker(AppPickerActivity.MODE_EXEMPT, config.exempt)
                    }
                }
            )
        }

        card("Never lied to") {
            addView(
                text(
                    "${Config.SPARE_PACKAGES.size} system components, so you keep control of the phone and of this app.",
                    13f,
                    cOnSurfaceVar,
                )
            )
            addView(
                text(
                    Config.SPARE_PACKAGES.sorted().joinToString(" · ") { labelOf(it) },
                    12f,
                    cOnSurface,
                ).apply { setPadding(0, dp(8), 0, 0) }
            )
        }
    }

    private fun emptyHint(message: String): View =
        text(message, 13f, cOnSurfaceVar, Typeface.ITALIC).apply { setPadding(0, dp(10), 0, 0) }

    private fun labelOf(pkg: String): String {
        if (pkg == "android") return "Android system"
        val pm = packageManager
        return runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() && it != pkg } ?: pkg.substringAfterLast('.')
    }

    private fun packageOfUid(uid: Int): String? {
        if (uid == Config.SYSTEM_UID) return null
        val pm = packageManager
        val packages = runCatching { pm.getPackagesForUid(uid) }.getOrNull()
        if (packages.isNullOrEmpty()) return null
        return packages.firstOrNull {
            runCatching { pm.getLaunchIntentForPackage(it) }.getOrNull() != null
        } ?: packages.firstOrNull()
    }

    private fun refusedTheOp(): List<String> {
        val declared = config.spoofers + config.exempt
        return records
            .filter { it.getStringArrayList(Bridge.REC_KINDS)?.contains(Config.RECORD_APPOPS) == true }
            .sortedByDescending { it.getLong(Bridge.REC_LAST) }
            .mapNotNull { packageOfUid(it.getInt(Bridge.REC_UID)) }
            .filter { it !in declared && it != packageName }
            .distinct()
    }

    private fun renderRefused() {
        val refused = refusedTheOp()
        if (refused.isEmpty()) return
        card("Asked for mock location, told no") {
            addView(
                text(
                    "Hiding the op from these apps is the point — a detector learns nothing. But a real spoofer cannot start either: the system refuses its test provider. If one of these is your spoofer, declare it and it alone will be told the truth. Restart it afterwards: most of them only ask once, when they launch.",
                    13f,
                    cOnSurfaceVar,
                )
            )
            for (pkg in refused) {
                addView(
                    appRow(pkg, "Declare") {
                        config.spoofers.add(pkg)
                        config.grantMockOp = true
                        persist()
                        render()
                    }
                )
            }
        }
    }

    private fun appRow(pkg: String, action: String = "Remove", onRemove: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        row.addView(ImageView(this).apply {
            setImageDrawable(runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull())
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(14) }
        })
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(text(labelOf(pkg), 15f, cOnSurface))
        texts.addView(text(pkg, 11f, cOnSurfaceVar))
        row.addView(texts)
        row.addView(
            MaterialButton(this, null, MR.attr.materialButtonOutlinedStyle).apply {
                text = action
                setOnClickListener { onRemove() }
            }
        )
        return row
    }

    private fun openPicker(mode: String, selected: Set<String>) {
        val intent = Intent(this, AppPickerActivity::class.java).apply {
            putExtra(AppPickerActivity.EXTRA_MODE, mode)
            putStringArrayListExtra(AppPickerActivity.EXTRA_SELECTED, ArrayList(selected))
        }
        picker.launch(intent)
    }

    // ------------------------------------------------------------------- log

    private fun renderLog() {
        if (records.isEmpty()) {
            if (live == null && (armed()?.get("location")?.toIntOrNull() ?: 0) > 0) {
                card("The log cannot be read") {
                    addView(
                        text(
                            "The module is armed and hiding, but the channel this app reads its log through is held by another module. The counters exist inside system_server, this screen just cannot reach them.",
                            13f,
                            cOnSurfaceVar,
                        )
                    )
                }
            } else {
                card("Nothing recorded") {
                    addView(
                        text(
                            "The module notes each app it lied to, and each location it cleaned. Nothing yet on this boot.",
                            13f,
                            cOnSurfaceVar,
                        )
                    )
                }
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
                addView(
                    recordRow(
                        appLabel(uid),
                        appPackage(uid),
                        "$count ×",
                        "$kinds · ${fmt.format(Date(last))}",
                    )
                )
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

    private fun recordRow(title: String, sub: String, right: String, rightSub: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f)
        }
        left.addView(text(title, 15f, cOnSurface))
        left.addView(text(sub, 11f, cOnSurfaceVar))
        row.addView(left)
        val rightBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        rightBox.addView(text(right, 15f, cPrimary).apply { gravity = Gravity.END })
        rightBox.addView(text(rightSub, 11f, cOnSurfaceVar).apply { gravity = Gravity.END })
        row.addView(rightBox)
        return row
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
