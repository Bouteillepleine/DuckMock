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
        val version = snapshot?.moduleVersion ?: Config.MODULE_VERSION
        val armedHooks = armed()?.get("location")?.toIntOrNull() ?: 0
        val where = when {
            live != null -> "running in system_server"
            armedHooks > 0 -> "armed in system_server"
            snapshot?.moduleInstalled == true -> "installed, not armed"
            snapshot?.rootAvailable == true -> "not installed"
            else -> "no live module"
        }
        return "v$version · $where"
    }

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
            card("Live in system_server") {
                addView(infoRow("Location marker", hookLine(state.getInt(Bridge.STATE_LOCATION_HOOKS))))
                addView(infoRow("Settings provider", hookLine(state.getInt(Bridge.STATE_SETTINGS_HOOKS))))
                addView(infoRow("App-ops gate", hookLine(state.getInt(Bridge.STATE_APPOPS_HOOKS))))
                addView(infoRow("Mock-location op", "#${state.getInt(Bridge.STATE_OP_CODE)}"))
                addView(
                    infoRow(
                        "Hook engine",
                        when (state.getString(Bridge.STATE_ENGINE)) {
                            "own" -> "started by DuckMock"
                            "adopted" -> "borrowed from another module"
                            else -> "not started"
                        },
                    )
                )
                addView(infoRow("Apps touched", "${state.getInt(Bridge.STATE_TOUCHED_APPS)}"))
            }
            card("Since this boot") {
                for (part in (state.getString(Bridge.STATE_CLEARED) ?: "").split(" · ")) {
                    val trimmed = part.trim()
                    if (trimmed.isEmpty()) continue
                    val count = trimmed.substringBefore(' ')
                    val what = trimmed.substringAfter(' ', "")
                    if (what.isEmpty()) {
                        addView(infoRow("Self-test", trimmed))
                    } else {
                        addView(infoRow(what.replaceFirstChar { it.uppercase() }, count))
                    }
                }
            }
        } else {
            val report = armed()
            if (report != null) {
                card("What the module reported when it armed") {
                    addView(infoRow("Location marker", hookLine(report["location"]?.toIntOrNull() ?: 0)))
                    addView(infoRow("Self-test", report["verdict"] ?: "unknown"))
                    addView(infoRow("App-ops gate", hookLine(report["appops"]?.toIntOrNull() ?: 0)))
                    addView(infoRow("Settings provider", hookLine(report["settings"]?.toIntOrNull() ?: 0)))
                    addView(
                        infoRow(
                            "Hook engine",
                            when (report["engine"]) {
                                "own" -> "started by DuckMock"
                                "adopted" -> "borrowed from another module"
                                else -> "not started"
                            },
                        )
                    )
                    addView(infoRow("Armed at", report["stamp"] ?: "—"))
                }
                if ((report["settings"]?.toIntOrNull() ?: 0) == 0) {
                    card("Why this screen is not live") {
                        addView(
                            text(
                                "The manager talks to the module through the settings provider, and another module hooked that method first. Only one hook per method is allowed, so the live view is unavailable while that module is active.",
                                13f,
                                cOnSurfaceVar,
                            )
                        )
                        addView(
                            text(
                                "Hiding is unaffected — the numbers above are what the module actually installed.",
                                13f,
                                cOnSurface,
                            )
                        )
                    }
                }
            } else {
                card("The module is not answering") {
                    addView(
                        text(
                            "Nothing is hiding anything right now. One of these is true:",
                            14f,
                            cOnSurface,
                        )
                    )
                    addView(text("•  it has not been flashed, or the phone has not rebooted since", 13f, cOnSurfaceVar))
                    addView(text("•  it is still arming — that happens about 20 seconds after boot", 13f, cOnSurfaceVar))
                    addView(text("•  the kill switch is on, or every switch on the Hiding tab is off", 13f, cOnSurfaceVar))
                }
            }
            val snap = snapshot
            if (snap?.rootAvailable == true) {
                card("On disk") {
                    addView(infoRow("Module", if (snap.moduleInstalled) "installed" else "not installed"))
                    addView(infoRow("Kill switch", if (snap.hooksKilled) "on" else "off"))
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
                        "Mock-location op held by",
                        if (holders.isEmpty()) "nothing" else holders.joinToString(", "),
                    )
                )
                addView(
                    text(
                        "Read as root, so this is the truth. An app DuckMock lies to sees the op held by nothing.",
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
                        "The module also stops itself after three arms in a row that do not stay up for two minutes. Turning this switch off clears that counter too.",
                        12f,
                        cOnSurfaceVar,
                    )
                )
                if (snapshot?.armReport?.contains("refused:") == true) {
                    addView(
                        MaterialButton(this@MainActivity, null, MR.attr.materialButtonOutlinedStyle).apply {
                            text = "Let it arm again"
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
            card("Root not granted to DuckMock") {
                addView(
                    text(
                        "The module itself runs fine without this — everything above still works. Root is only needed here to keep your settings across a reboot, and to use the kill switch.",
                        13f,
                        cOnSurfaceVar,
                    )
                )
                addView(
                    text(
                        "Grant it in your root manager: Superuser → DuckMock → allow.",
                        13f,
                        cOnSurface,
                    )
                )
                addView(
                    MaterialButton(this@MainActivity, null, MR.attr.materialButtonOutlinedStyle).apply {
                        text = "Check again"
                        setOnClickListener { reload() }
                    }
                )
            }
        }
    }

    private fun hookLine(count: Int): String =
        if (count == 0) "not armed" else "$count hook${if (count == 1) "" else "s"}"

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
            state.getInt(Bridge.STATE_SETTINGS_HOOKS) > 0
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
                    "Your real position is being reported.",
                    cCard,
                    cOnSurfaceVar,
                )
            }
        )

        card("Where to be") {
            addView(valueField("Latitude", spoof.latitude.toString()) {
                it.toDoubleOrNull()?.let { v -> spoof = spoof.copy(latitude = v) }
            })
            addView(valueField("Longitude", spoof.longitude.toString()) {
                it.toDoubleOrNull()?.let { v -> spoof = spoof.copy(longitude = v) }
            })
            addView(valueField("Altitude (m)", spoof.altitude.toString()) {
                it.toDoubleOrNull()?.let { v -> spoof = spoof.copy(altitude = v) }
            })
            addView(valueField("Accuracy (m)", spoof.accuracy.toString()) {
                it.toFloatOrNull()?.let { v -> spoof = spoof.copy(accuracy = v) }
            })
            addView(valueField("Wander (m)", spoof.jitterMetres.toString()) {
                it.toFloatOrNull()?.let { v -> spoof = spoof.copy(jitterMetres = v) }
            })
            addView(
                text(
                    "A position that never moves by a single centimetre is a tell in itself. Wander adds a small random drift on each update; set it to 0 to stand perfectly still.",
                    12f,
                    cOnSurfaceVar,
                )
            )
            addView(valueField("Name (optional)", spoof.label) { spoof = spoof.copy(label = it) })
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
            row.addView(
                MaterialButton(this@MainActivity, null, MR.attr.materialButtonOutlinedStyle).apply {
                    text = "Use my position"
                    setOnClickListener { fillFromReal() }
                }
            )
            row.addView(
                MaterialButton(this@MainActivity, null, MR.attr.materialButtonOutlinedStyle).apply {
                    text = "Save as favourite"
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginStart = dp(8) }
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
            addView(row)
        }

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

        card("How this works") {
            addView(
                text(
                    "DuckMock registers itself as a location provider and pushes your chosen position once a second. It grants itself the mock-location permission through its own app-ops gate, so the developer-options picker stays empty, and it strips the mock marker from the positions it produces — so apps see an ordinary GPS fix.",
                    13f,
                    cOnSurfaceVar,
                )
            )
            addView(
                text(
                    "It does not touch GNSS itself: an app that watches the satellite list can still notice a fix with no satellites behind it.",
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

    private fun valueField(label: String, value: String, onChange: (String) -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(2))
        }
        box.addView(text(label, 12f, cOnSurfaceVar))
        box.addView(EditText(this).apply {
            setText(value)
            setSingleLine()
            setTextColor(cOnSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = onChange(s?.toString().orEmpty())
            })
        })
        return box
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
