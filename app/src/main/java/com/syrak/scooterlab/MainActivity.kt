package com.syrak.scooterlab

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * ============================================================================
 *  Syrak ScooterLab — Region-Aware "Police Mode / Panic Button"
 * ============================================================================
 *
 *  This activity is intentionally self-contained: every view is built
 *  programmatically (LinearLayout / ScrollView / TextView / Button) and the
 *  whole feature lives in this one file.
 *
 *  The core idea
 *  -------------
 *  Scooter controllers ship with a *region-specific* stock speed baseline
 *  (a US unit leaves the factory at ~32 km/h, a German unit at ~20 km/h, a
 *  generic EU unit at ~25 km/h, ...).  Tuners temporarily raise that ceiling
 *  with the so-called "German Maneuver": a *volatile* speed patch written to
 *  the controller's RAM that disappears on reboot.
 *
 *  Police Mode is the safety counter-measure. When the rider taps the panic
 *  button we:
 *      1. flush the volatile RAM speed patch,
 *      2. restore the controller limit to the *detected regional* stock value
 *         (NOT a hard-coded global constant), and
 *      3. log a security event naming the region.
 *
 *  The region is auto-detected from the system locale, but the rider can also
 *  pin an explicit "selected baseline" which always wins.
 *
 *  Demo Mode and real BLE both flow through the same [pushControllerFrame]
 *  transport, so the panic logic is identical whether or not hardware is
 *  attached.
 * ============================================================================
 */

// ---------------------------------------------------------------------------
//  Region model
// ---------------------------------------------------------------------------

/**
 * A regional stock baseline for a scooter controller.
 *
 * @param countryCode  ISO-3166 alpha-2 code (e.g. "US", "DE"). "--" = fallback.
 * @param displayName  Human readable region name shown in the UI / logs.
 * @param stockSpeedKmh The legal / factory speed the controller ships with in
 *                      that region. Police Mode restores exactly this value.
 * @param flag         Emoji used purely for UI flair.
 */
data class RegionProfile(
    val countryCode: String,
    val displayName: String,
    val stockSpeedKmh: Int,
    val flag: String
)

/**
 * Static table of regional stock baselines.
 *
 * Values mirror common factory/legal limits for consumer e-scooters. They are
 * data, not logic, so a new region is a one-line addition.
 */
object RegionRegistry {

    /** Fallback when the locale does not map to a known region. */
    val INTERNATIONAL = RegionProfile("--", "International (Default)", 25, "🌐")

    private val table: Map<String, RegionProfile> = listOf(
        RegionProfile("US", "United States", 32, "🇺🇸"),
        RegionProfile("CA", "Canada", 32, "🇨🇦"),
        RegionProfile("MX", "Mexico", 30, "🇲🇽"),
        RegionProfile("BR", "Brazil", 30, "🇧🇷"),
        RegionProfile("DE", "Germany", 20, "🇩🇪"),
        RegionProfile("AT", "Austria", 20, "🇦🇹"),
        RegionProfile("CH", "Switzerland", 20, "🇨🇭"),
        RegionProfile("SE", "Sweden", 20, "🇸🇪"),
        RegionProfile("NO", "Norway", 20, "🇳🇴"),
        RegionProfile("DK", "Denmark", 20, "🇩🇰"),
        RegionProfile("PL", "Poland", 20, "🇵🇱"),
        RegionProfile("JP", "Japan", 20, "🇯🇵"),
        RegionProfile("FR", "France", 25, "🇫🇷"),
        RegionProfile("IT", "Italy", 25, "🇮🇹"),
        RegionProfile("ES", "Spain", 25, "🇪🇸"),
        RegionProfile("PT", "Portugal", 25, "🇵🇹"),
        RegionProfile("NL", "Netherlands", 25, "🇳🇱"),
        RegionProfile("BE", "Belgium", 25, "🇧🇪"),
        RegionProfile("GB", "United Kingdom", 25, "🇬🇧"),
        RegionProfile("IE", "Ireland", 25, "🇮🇪"),
        RegionProfile("FI", "Finland", 25, "🇫🇮"),
        RegionProfile("AU", "Australia", 25, "🇦🇺"),
        RegionProfile("NZ", "New Zealand", 25, "🇳🇿"),
        RegionProfile("SG", "Singapore", 25, "🇸🇬")
    ).associateBy { it.countryCode }

    /** Resolve a raw ISO-3166 alpha-2 code (e.g. from [Locale]) to a profile. */
    fun resolve(countryCode: String?): RegionProfile {
        val cc = countryCode?.trim()?.uppercase(Locale.US).orEmpty()
        return table[cc] ?: INTERNATIONAL
    }

    /** Auto-detect the region from the device's current system locale. */
    fun detectFromLocale(): RegionProfile = resolve(Locale.getDefault().country)

    /** All selectable baselines, sorted, for the "change region" control. */
    fun selectable(): List<RegionProfile> =
        (table.values + INTERNATIONAL).distinct().sortedBy { it.displayName }

    fun isKnown(countryCode: String): Boolean =
        table.containsKey(countryCode.uppercase(Locale.US))
}

/** Visual state of the top status banner. */
private enum class BannerState { STOCK, MANEUVER, POLICE }

// ---------------------------------------------------------------------------
//  Activity
// ---------------------------------------------------------------------------

class MainActivity : AppCompatActivity() {

    // ---- Link / session state ------------------------------------------------
    /** Demo Mode needs no hardware; the transport just logs. */
    private var demoMode = true
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    // ---- Region state --------------------------------------------------------
    /** Region resolved from the system locale at boot. */
    private var detectedRegion: RegionProfile = RegionRegistry.INTERNATIONAL
    /** Explicit rider-selected baseline; when set it always wins. */
    private var regionOverride: RegionProfile? = null
    /** The region actually in force right now. */
    private val activeRegion: RegionProfile
        get() = regionOverride ?: detectedRegion

    // ---- Controller state ----------------------------------------------------
    /** True while a volatile German-Maneuver RAM patch is live. */
    private var ramPatchActive = false
    /** The controller's current enforced speed limit (km/h). */
    private var currentLimitKmh = 25
    /** Simulated / telemetry live speed (km/h). */
    private var liveSpeedKmh = 0.0
    private var bannerState = BannerState.STOCK

    // ---- UI references -------------------------------------------------------
    private lateinit var statusBanner: TextView
    private lateinit var liveSpeedValue: TextView
    private lateinit var limitValue: TextView
    private lateinit var regionValue: TextView
    private lateinit var consoleView: TextView
    private lateinit var policeButton: Button
    private lateinit var maneuverButton: Button
    private lateinit var linkButton: Button

    // ---- Helpers -------------------------------------------------------------
    private val uiHandler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val rng = Random(System.currentTimeMillis())

    // =========================================================================
    //  Lifecycle
    // =========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Detect the region before we build anything so the first paint is
        //    already correct.
        detectedRegion = RegionRegistry.detectFromLocale()
        currentLimitKmh = activeRegion.stockSpeedKmh

        // 2. Build the programmatic UI.
        setContentView(buildRoot())

        // 3. Seed the console.
        appendConsole("boot: Syrak ScooterLab v1.0 — safety console ready")
        appendConsole(
            "region: auto-detected ${activeRegion.flag} ${activeRegion.displayName} " +
                "(stock ${activeRegion.stockSpeedKmh} km/h)"
        )
        appendConsole("link: demo mode engaged (no BLE hardware required)")

        refreshUi()
        startSpeedSimulation()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacksAndMessages(null)
    }

    // =========================================================================
    //  UI construction (100% programmatic)
    // =========================================================================

    private fun buildRoot(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG)
            setPadding(dp(16), dp(20), dp(16), dp(28))
        }

        column.addView(buildHeader())
        column.addView(buildStatusBanner())
        column.addView(buildMetricsRow())
        column.addView(buildRegionCard())
        column.addView(buildSafetySection())
        column.addView(buildConsoleCard())

        return ScrollView(this).apply {
            setBackgroundColor(COLOR_BG)
            isFillViewport = true
            addView(column)
        }
    }

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        header.addView(
            TextView(this).apply {
                text = "Syrak ScooterLab"
                setTextColor(COLOR_TEXT)
                textSize = 26f
                setTypeface(typeface, Typeface.BOLD)
            }
        )
        header.addView(
            TextView(this).apply {
                text = "Region-Aware Safety Console"
                setTextColor(COLOR_TEXT_DIM)
                textSize = 13f
            }
        )
        return withBottomMargin(header, 16)
    }

    private fun buildStatusBanner(): View {
        statusBanner = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(COLOR_STOCK, 12f)
        }
        return withBottomMargin(statusBanner, 16)
    }

    private fun buildMetricsRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val live = metricCard("LIVE SPEED")
        liveSpeedValue = live.second

        val limit = metricCard("CONTROLLER LIMIT")
        limitValue = limit.second

        row.addView(rowChild(live.first))
        row.addView(spacer(12))
        row.addView(rowChild(limit.first))
        return withBottomMargin(row, 16)
    }

    /** Builds a titled metric card and returns (container, valueView). */
    private fun metricCard(label: String): Pair<View, TextView> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(COLOR_CARD, 14f)
        }
        card.addView(
            TextView(this).apply {
                text = label
                setTextColor(COLOR_TEXT_DIM)
                textSize = 11f
                letterSpacing = 0.08f
            }
        )
        val value = TextView(this).apply {
            text = "—"
            setTextColor(COLOR_TEXT)
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        }
        card.addView(value)
        return card to value
    }

    private fun buildRegionCard(): View {
        val card = cardContainer()
        card.addView(sectionTitle("REGION BASELINE"))

        regionValue = TextView(this).apply {
            setTextColor(COLOR_TEXT)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }
        card.addView(regionValue)

        card.addView(
            TextView(this).apply {
                text = "Police Mode restores this region's original stock speed — " +
                    "never a rigid global constant."
                setTextColor(COLOR_TEXT_DIM)
                textSize = 12f
                setPadding(0, dp(4), 0, dp(12))
            }
        )

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val detect = makeButton("AUTO-DETECT", COLOR_ACCENT)
        detect.setOnClickListener { detectRegion() }
        val change = makeButton("CHANGE REGION ▸", COLOR_CARD_ALT)
        change.setOnClickListener { cycleRegion() }
        row.addView(rowChild(detect))
        row.addView(spacer(10))
        row.addView(rowChild(change))
        card.addView(row)

        return withBottomMargin(card, 16)
    }

    private fun buildSafetySection(): View {
        val card = cardContainer()
        card.addView(sectionTitle("SAFETY / DASHBOARD"))

        // ---- The headline panic control -------------------------------------
        policeButton = makeButton("⚠   POLICE MODE (PANIC)", COLOR_POLICE)
        policeButton.textSize = 17f
        policeButton.setPadding(dp(16), dp(18), dp(16), dp(18))
        policeButton.setOnClickListener { triggerPoliceMode() }
        card.addView(policeButton)

        card.addView(
            TextView(this).apply {
                text = "One tap: flush the volatile RAM speed patch and restore your " +
                    "regional stock profile instantly."
                setTextColor(COLOR_TEXT_DIM)
                textSize = 12f
                setPadding(0, dp(8), 0, dp(14))
            }
        )

        // ---- Secondary controls ---------------------------------------------
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        maneuverButton = makeButton("APPLY GERMAN MANEUVER (TEST)", COLOR_MANEUVER)
        maneuverButton.textSize = 12f
        maneuverButton.setOnClickListener { applyGermanManeuver() }

        linkButton = makeButton("CONNECT BLE", COLOR_CARD_ALT)
        linkButton.textSize = 12f
        linkButton.setOnClickListener { toggleLinkMode() }

        row.addView(rowChild(maneuverButton))
        row.addView(spacer(10))
        row.addView(rowChild(linkButton))
        card.addView(row)

        return withBottomMargin(card, 16)
    }

    private fun buildConsoleCard(): View {
        val card = cardContainer()
        card.addView(sectionTitle("CONSOLE"))

        consoleView = TextView(this).apply {
            setTextColor(COLOR_CONSOLE_TEXT)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(COLOR_CONSOLE_BG)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            movementMethod = ScrollingMovementMethod()
            maxLines = 14
        }
        card.addView(consoleView)
        return card
    }

    // =========================================================================
    //  UI helpers
    // =========================================================================

    private fun cardContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(COLOR_CARD, 16f)
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(COLOR_TEXT_DIM)
        textSize = 11f
        letterSpacing = 0.12f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(10))
    }

    private fun makeButton(label: String, color: Int): Button = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 14f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        background = rounded(color, 12f)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        stateListAnimator = null
    }

    private fun rowChild(view: View): View {
        view.layoutParams =
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        return view
    }

    private fun spacer(widthDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(widthDp), 1)
    }

    private fun withBottomMargin(view: View, marginDp: Int): View {
        (view.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.bottomMargin = dp(marginDp)
        } ?: run {
            view.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(marginDp) }
        }
        return view
    }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
        }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).roundToInt()

    // =========================================================================
    //  Region handling
    // =========================================================================

    /** Re-detect the region from the system locale and drop any manual pin. */
    private fun detectRegion() {
        detectedRegion = RegionRegistry.detectFromLocale()
        regionOverride = null
        appendConsole(
            "region: re-detected ${activeRegion.flag} ${activeRegion.displayName} " +
                "(stock ${activeRegion.stockSpeedKmh} km/h)"
        )
        // If no maneuver is live, the controller should track the new baseline.
        if (!ramPatchActive) {
            currentLimitKmh = activeRegion.stockSpeedKmh
            pushControllerFrame(
                buildSpeedLimitFrame(currentLimitKmh, volatile = false),
                "set stock limit ${currentLimitKmh} km/h (${activeRegion.countryCode})"
            )
        }
        refreshUi()
    }

    /** Cycle to the next selectable baseline (manual region pin). */
    private fun cycleRegion() {
        val options = RegionRegistry.selectable()
        val currentIndex = options.indexOfFirst { it.countryCode == activeRegion.countryCode }
        val next = options[(currentIndex + 1) % options.size]
        regionOverride = next
        appendConsole(
            "region: baseline pinned to ${next.flag} ${next.displayName} " +
                "(stock ${next.stockSpeedKmh} km/h)"
        )
        if (!ramPatchActive) {
            currentLimitKmh = next.stockSpeedKmh
            pushControllerFrame(
                buildSpeedLimitFrame(currentLimitKmh, volatile = false),
                "set stock limit ${currentLimitKmh} km/h (${next.countryCode})"
            )
        }
        refreshUi()
    }

    // =========================================================================
    //  German Maneuver (volatile RAM patch)
    // =========================================================================

    /**
     * Simulates applying the German Maneuver: a *volatile* RAM speed patch that
     * lifts the controller ceiling above the regional stock value.
     *
     * Real implementations write a model-specific register frame to the
     * controller's RAM; here we build a representative frame and route it
     * through the same transport the panic button uses.
     */
    private fun applyGermanManeuver() {
        if (ramPatchActive) {
            appendConsole("maneuver: already active — ignoring duplicate request")
            return
        }
        val unlocked = activeRegion.stockSpeedKmh + UNLOCK_DELTA_KMH
        ramPatchActive = true
        currentLimitKmh = unlocked
        pushControllerFrame(
            buildSpeedLimitFrame(unlocked, volatile = true),
            "RAM speed patch → $unlocked km/h (volatile, ${activeRegion.countryCode})"
        )
        appendConsole("maneuver: German Maneuver engaged — volatile patch is LIVE")
        bannerState = BannerState.MANEUVER
        refreshUi()
    }

    /**
     * Flushes the volatile RAM speed patch. This is the first half of Police
     * Mode: it drops the temporary ceiling without touching persistent NVM.
     */
    private fun flushVolatileRamPatch() {
        if (!ramPatchActive) {
            appendConsole("maneuver: no volatile RAM patch present — flush skipped")
            return
        }
        // 0x00 limit + volatile flag = "clear the RAM override".
        pushControllerFrame(
            buildSpeedLimitFrame(0, volatile = true),
            "flush volatile RAM speed patch"
        )
        ramPatchActive = false
        appendConsole("maneuver: volatile RAM speed patch flushed")
    }

    // =========================================================================
    //  Police Mode (panic button)
    // =========================================================================

    /**
     * The panic action. Order matters:
     *   1. flush the volatile RAM patch,
     *   2. revert the controller to the *regional* stock baseline,
     *   3. repaint the banner + speed indicators,
     *   4. log the security event naming the region.
     */
    private fun triggerPoliceMode() {
        // 1. Kill the volatile patch first so nothing can re-raise the ceiling.
        flushVolatileRamPatch()

        // 2. Restore the region-specific stock profile (not a global constant).
        revertToRegionalStock()

        // 3. Reflect the change everywhere in the UI.
        bannerState = BannerState.POLICE
        refreshUi()

        // 4. Security audit trail.
        logSecurityEvent(activeRegion)

        Toast.makeText(
            this,
            "Police Mode: stock restored for ${activeRegion.displayName}",
            Toast.LENGTH_SHORT
        ).show()

        // Return the banner to its resting "stock" state shortly after.
        uiHandler.postDelayed({
            if (bannerState == BannerState.POLICE) {
                bannerState = BannerState.STOCK
                refreshUi()
            }
        }, POLICE_BANNER_HOLD_MS)
    }

    /** Reverts speed metrics + controller limits to the regional stock value. */
    private fun revertToRegionalStock() {
        val stock = activeRegion.stockSpeedKmh
        currentLimitKmh = stock
        // Persist the stock limit back to the controller (non-volatile).
        pushControllerFrame(
            buildSpeedLimitFrame(stock, volatile = false),
            "restore regional stock limit $stock km/h (${activeRegion.countryCode})"
        )
        // Decay the live reading toward the restored ceiling immediately.
        if (liveSpeedKmh > stock) liveSpeedKmh = stock.toDouble()
        appendConsole(
            "controller: speed limit reverted to regional stock " +
                "$stock km/h for ${activeRegion.displayName}"
        )
    }

    /** Writes the mandatory security event to the console card. */
    private fun logSecurityEvent(region: RegionProfile) {
        appendConsole(
            "security: police mode triggered — restored regional stock profile for " +
                "${region.displayName}"
        )
    }

    // =========================================================================
    //  Transport (Demo Mode + real BLE share this path)
    // =========================================================================

    /**
     * Routes a controller frame to the active transport.
     *
     * In Demo Mode (or when no GATT link is present) it simply logs, so the
     * panic logic is fully testable without hardware. With a live BLE link it
     * writes the frame to the controller characteristic.
     */
    @SuppressLint("MissingPermission")
    private fun pushControllerFrame(payload: ByteArray, label: String) {
        val activeGatt = gatt
        val characteristic = writeCharacteristic

        if (demoMode || activeGatt == null || characteristic == null) {
            appendConsole("demo: link simulated → $label")
            return
        }

        try {
            characteristic.value = payload
            val ok = activeGatt.writeCharacteristic(characteristic)
            appendConsole(if (ok) "ble: tx ok → $label" else "ble: tx failed → $label")
        } catch (se: SecurityException) {
            appendConsole("ble: permission denied → $label")
        } catch (t: Throwable) {
            appendConsole("ble: error (${t.javaClass.simpleName}) → $label")
        }
    }

    /**
     * Builds a representative speed-limit frame.
     *
     * Layout: [0xA5 start][0x21 set-speed-limit][limit][flags][xor checksum]
     * `flags` bit 0 marks the write as volatile (RAM) rather than persistent.
     * Real controllers use model-specific registers; this keeps the demo
     * transport-shaped without claiming a specific vendor protocol.
     */
    private fun buildSpeedLimitFrame(limitKmh: Int, volatile: Boolean): ByteArray {
        val limit = limitKmh.coerceIn(0, 255).toByte()
        val flags = if (volatile) 0x01.toByte() else 0x00.toByte()
        val body = byteArrayOf(0xA5.toByte(), 0x21.toByte(), limit, flags)
        val checksum = body.fold(0) { acc, b -> acc xor b.toInt() }.toByte()
        return body + checksum
    }

    /**
     * Toggles between Demo Mode and a (placeholder) real BLE link.
     *
     * A production build would run the full scan/connect/GATT-discovery flow
     * here; the panic button only depends on [pushControllerFrame], so nothing
     * else in this file needs to change when hardware is wired in.
     */
    private fun toggleLinkMode() {
        demoMode = !demoMode
        if (demoMode) {
            gatt = null
            writeCharacteristic = null
            appendConsole("link: switched to DEMO mode")
        } else {
            appendConsole("link: BLE mode requested — awaiting controller link")
            appendConsole("link: (no GATT link attached; frames will buffer as demo)")
        }
        linkButton.text = if (demoMode) "CONNECT BLE" else "DEMO MODE"
        refreshUi()
    }

    // =========================================================================
    //  UI refresh + console
    // =========================================================================

    private fun refreshUi() {
        // Region line.
        regionValue.text = "${activeRegion.flag}  ${activeRegion.displayName}  ·  " +
            "stock ${activeRegion.stockSpeedKmh} km/h"

        // Metric cards.
        limitValue.text = "$currentLimitKmh km/h"
        liveSpeedValue.text = "${liveSpeedKmh.roundToInt()} km/h"

        // Banner.
        when (bannerState) {
            BannerState.STOCK -> {
                statusBanner.text =
                    "STOCK / SAFE — ${activeRegion.displayName} (${activeRegion.stockSpeedKmh} km/h)"
                statusBanner.background = rounded(COLOR_STOCK, 12f)
            }
            BannerState.MANEUVER -> {
                statusBanner.text =
                    "⚠ GERMAN MANEUVER ACTIVE — volatile RAM patch ($currentLimitKmh km/h)"
                statusBanner.background = rounded(COLOR_MANEUVER, 12f)
            }
            BannerState.POLICE -> {
                statusBanner.text =
                    "🛡 POLICE MODE — STOCK RESTORED (${activeRegion.displayName})"
                statusBanner.background = rounded(COLOR_POLICE_ENGAGED, 12f)
            }
        }

        // The maneuver button is only actionable when no patch is already live.
        maneuverButton.isEnabled = !ramPatchActive
        maneuverButton.alpha = if (ramPatchActive) 0.5f else 1f
    }

    private fun appendConsole(line: String) {
        val stamp = timeFmt.format(Date())
        val entry = "[$stamp] $line"
        if (::consoleView.isInitialized) {
            consoleView.append(if (consoleView.text.isEmpty()) entry else "\n$entry")
        }
    }

    // =========================================================================
    //  Live speed simulation (keeps the indicators meaningful in Demo Mode)
    // =========================================================================

    private fun startSpeedSimulation() {
        uiHandler.post(object : Runnable {
            override fun run() {
                stepSpeed()
                uiHandler.postDelayed(this, 1000L)
            }
        })
    }

    private fun stepSpeed() {
        // The live reading is always clamped to the controller's active limit.
        // When Police Mode drops the limit, the reading decays back down with it.
        val ceiling = currentLimitKmh.toDouble()
        val drift = rng.nextDouble(-3.5, 4.5)
        liveSpeedKmh = (liveSpeedKmh + drift).coerceIn(0.0, ceiling)

        liveSpeedValue.text = "${liveSpeedKmh.roundToInt()} km/h"
    }

    // =========================================================================
    //  Palette
    // =========================================================================

    private companion object {
        const val UNLOCK_DELTA_KMH = 15      // how far the maneuver lifts the ceiling
        const val POLICE_BANNER_HOLD_MS = 4000L

        val COLOR_BG = Color.parseColor("#0E1116")
        val COLOR_CARD = Color.parseColor("#1B2129")
        val COLOR_CARD_ALT = Color.parseColor("#2A323D")
        val COLOR_TEXT = Color.parseColor("#ECEFF1")
        val COLOR_TEXT_DIM = Color.parseColor("#90A4AE")
        val COLOR_ACCENT = Color.parseColor("#00897B")
        val COLOR_STOCK = Color.parseColor("#2E7D32")
        val COLOR_MANEUVER = Color.parseColor("#EF6C00")
        val COLOR_POLICE = Color.parseColor("#BF360C")        // deep orange/red
        val COLOR_POLICE_ENGAGED = Color.parseColor("#00695C")
        val COLOR_CONSOLE_BG = Color.parseColor("#05070A")
        val COLOR_CONSOLE_TEXT = Color.parseColor("#7CE38B")
    }
}
