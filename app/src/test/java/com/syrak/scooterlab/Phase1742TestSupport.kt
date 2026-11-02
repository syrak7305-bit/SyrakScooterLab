package com.syrak.scooterlab

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.syrak.scooterlab.recognition.HardwareTestJson
import com.syrak.scooterlab.recognition.HardwareTestRecord
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

/**
 * PHASE 17.4.2 — Hilfsmittel fuer Laufzeitpruefungen der echten [MainActivity].
 *
 * WICHTIG: Das ist KEIN Geraetetest. Die Activity laeuft unter Robolectric auf
 * der JVM — mit echtem Lifecycle, echter View-Hierarchie und echten
 * SharedPreferences, aber ohne echte Hardware, ohne echtes BLE und ohne echtes
 * Android-System. Ergebnisse daraus duerfen nicht als Geraetebeleg gelten.
 */
object Rt {

    const val PREFS = "SyrakPrefs"
    const val KEY_PRIMARY = "hardware_tests_json"
    const val KEY_BACKUP = "hardware_tests_json_backup"

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Setzt die Start-Gates (Haftungshinweis, Laenderhinweis, Onboarding,
     * Setup-Wizard), damit die Activity direkt in die Hauptansicht geht.
     * Es werden ausschliesslich Test-Voreinstellungen geschrieben.
     */
    fun acceptStartGates(ctx: Context) {
        prefs(ctx).edit()
            .putBoolean("disclaimers_accepted", true)
            .putBoolean("country_notice_accepted", true)
            .putBoolean("onboarding_seen", true)
            .putString("profile_setup_completed", "true")
            .commit()
    }

    /** Laesst alle geplanten Main-Looper-Aufgaben (Splash) ablaufen. */
    fun idle(seconds: Long = 20) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
    }

    fun allViews(root: View): List<View> {
        val out = mutableListOf<View>()
        fun walk(v: View) {
            out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    fun texts(root: View): List<String> =
        allViews(root).filterIsInstance<TextView>().mapNotNull { it.text?.toString() }

    fun hasText(root: View, value: String): Boolean = texts(root).any { it == value }

    fun hasTextContaining(root: View, value: String): Boolean =
        texts(root).any { it.contains(value) }

    /** Findet die erste View mit genau diesem Text. */
    fun viewWithText(root: View, value: String): View? =
        allViews(root).firstOrNull { it is TextView && it.text?.toString() == value }

    /**
     * Klickt ein Element anhand seiner Beschriftung. Buttons tragen den
     * Listener selbst; Filterzeilen sind Container, daher wird nach oben
     * gelaufen, bis eine klickbare View gefunden ist.
     */
    fun click(root: View, label: String): Boolean {
        val target = viewWithText(root, label) ?: return false
        var current: View? = target
        var hops = 0
        while (current != null && hops < 8) {
            if (current.isClickable) {
                current.performClick()
                return true
            }
            current = current.parent as? View
            hops++
        }
        return false
    }

    fun buttons(root: View): List<Button> = allViews(root).filterIsInstance<Button>()

    fun buttonLabels(root: View): List<String> = buttons(root).mapNotNull { it.text?.toString() }

    fun editTextByHint(root: View, hint: String): EditText? =
        allViews(root).filterIsInstance<EditText>()
            .firstOrNull { it.hint?.toString() == hint }

    fun editTexts(root: View): List<EditText> = allViews(root).filterIsInstance<EditText>()

    fun spinners(root: View): List<Spinner> = allViews(root).filterIsInstance<Spinner>()

    fun latestDialog(): Dialog? = ShadowDialog.getLatestDialog()

    fun latestAlertDialog(): AlertDialog? = ShadowDialog.getLatestDialog() as? AlertDialog

    fun clickDialogButton(dialog: AlertDialog, which: Int) {
        dialog.getButton(which).performClick()
    }

    fun dialogTexts(dialog: Dialog): List<String> = texts(dialog.window!!.decorView)

    fun record(
        id: String,
        manufacturer: ScooterManufacturer,
        model: String,
        date: String,
        fingerprintId: String? = null,
        addressMasked: String? = null,
        notes: String? = null
    ) = HardwareTestRecord(
        id = id,
        manufacturer = manufacturer,
        modelName = model,
        testDate = date,
        expectedFingerprintId = fingerprintId,
        addressMasked = addressMasked,
        notes = notes
    )

    /** Schreibt einen gueltigen Hauptstand direkt in die Test-Preferences. */
    fun seed(ctx: Context, records: List<HardwareTestRecord>) {
        prefs(ctx).edit().putString(KEY_PRIMARY, HardwareTestJson.encode(records)).commit()
    }

    fun storedJson(ctx: Context): String? = prefs(ctx).getString(KEY_PRIMARY, null)

    fun storedBackup(ctx: Context): String? = prefs(ctx).getString(KEY_BACKUP, null)

    fun storedRecords(ctx: Context): List<HardwareTestRecord> {
        val json = storedJson(ctx) ?: return emptyList()
        return HardwareTestJson.decode(json).records
    }

    @Suppress("UNCHECKED_CAST")
    fun liveRecords(activity: Activity): List<HardwareTestRecord> {
        val field = MainActivity::class.java.getDeclaredField("hardwareTests")
        field.isAccessible = true
        return field.get(activity) as List<HardwareTestRecord>
    }

    fun privateField(activity: Activity, name: String): Any? {
        val field = MainActivity::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(activity)
    }

    fun callPrivate(activity: Activity, name: String, vararg args: Any?) {
        val method = MainActivity::class.java.declaredMethods.firstOrNull { m ->
            m.name == name && m.parameterCount == args.size &&
                m.parameterTypes.withIndex().all { (i, type) ->
                    args[i] == null || type.isAssignableFrom(args[i]!!::class.java)
                }
        } ?: error("Methode $name(${args.joinToString { it?.javaClass?.name ?: "null" }}) nicht gefunden")
        method.isAccessible = true
        method.invoke(activity, *args)
    }
}
