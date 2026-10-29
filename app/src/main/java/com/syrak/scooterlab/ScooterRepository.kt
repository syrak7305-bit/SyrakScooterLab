package com.syrak.scooterlab

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/* ============================================================================
 *  SCOOTER-REPOSITORY
 *  ----------------------------------------------------------------------------
 *  Kapselt die PERSISTENZ der App (SharedPreferences) hinter einer sauberen API:
 *    • Disclaimer-/Reconnect-Einstellungen
 *    • Region + Adapter-State (zuletzt genutztes Protokoll)
 *    • PIN fuer die Wegfahrsperre — SICHER via PBKDF2 (NICHT mehr Klartext!)
 *    • Tuning-Profile (Speichern/Laden)
 *    • Trip-Computer-Daten (Fahrtstatistik)
 *
 *  BUGFIX: Die PIN wurde zuvor im KLARTEXT in SharedPreferences gespeichert und
 *  per String-Vergleich geprueft. Jetzt wird ein zufaelliges Salt erzeugt und
 *  die PIN mit PBKDF2 (HmacSHA256, 120_000 Iterationen) gehasht. Gespeichert
 *  werden nur Salt + Hash (Base64). Die Klartext-PIN verlaesst nie den Speicher.
 * ==========================================================================*/
class ScooterRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Trip-Computer (Fahrtstatistik) — vom UI genutzt. */
    val trip = TripData()

    /* ------------------------------------------------------------------ */
    /*  EINSTELLUNGEN                                                      */
    /* ------------------------------------------------------------------ */
    var disclaimersAccepted: Boolean
        get() = prefs.getBoolean(KEY_DISCLAIMERS, false)
        set(value) = prefs.edit().putBoolean(KEY_DISCLAIMERS, value).apply()

    var autoReconnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RECONNECT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RECONNECT, value).apply()

    var region: Region
        get() = Region.fromCountry(prefs.getString(KEY_REGION, null) ?: Locale.getDefault().country)
        set(value) = prefs.edit().putString(KEY_REGION, value.code).apply()

    /** Zuletzt genutztes Protokoll (Adapter-State). */
    var lastProtocol: BleProtocol
        get() = try {
            BleProtocol.valueOf(prefs.getString(KEY_PROTOCOL, BleProtocol.NINEBOT.name) ?: BleProtocol.NINEBOT.name)
        } catch (_: Exception) { BleProtocol.NINEBOT }
        set(value) = prefs.edit().putString(KEY_PROTOCOL, value.name).apply()

    /** Zuletzt genutzte Adapter-ID (Adapter-State). */
    var lastAdapterId: String
        get() = prefs.getString(KEY_ADAPTER_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ADAPTER_ID, value).apply()

    /* ------------------------------------------------------------------ */
    /*  WEGFAHRSPERRE (PIN, PBKDF2)                                        */
    /* ------------------------------------------------------------------ */
    var isLocked: Boolean
        get() = prefs.getBoolean(KEY_LOCKED, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCKED, value).apply()

    /** true, wenn bereits eine PIN gesetzt wurde. */
    val hasPin: Boolean
        get() = prefs.contains(KEY_PIN_HASH) && prefs.contains(KEY_PIN_SALT)

    /**
     * Setzt eine neue PIN. Es wird ein frisches 16-Byte-Salt erzeugt und die PIN
     * mit PBKDF2 gehasht. Die Klartext-PIN wird NICHT gespeichert.
     */
    fun setPin(pin: String) {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt, ITERATIONS, KEY_LENGTH_BYTES)
        prefs.edit()
            .putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .putInt(KEY_PIN_ITER, ITERATIONS)
            .apply()
    }

    /** Verifiziert eine PIN gegen den gespeicherten PBKDF2-Hash (konstante Zeit). */
    fun verifyPin(pin: String): Boolean {
        val saltB64 = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val hashB64 = prefs.getString(KEY_PIN_HASH, null) ?: return false
        val iterations = prefs.getInt(KEY_PIN_ITER, ITERATIONS)
        val salt = try { Base64.decode(saltB64, Base64.NO_WRAP) } catch (_: Exception) { return false }
        val expected = try { Base64.decode(hashB64, Base64.NO_WRAP) } catch (_: Exception) { return false }
        val actual = derive(pin, salt, iterations, expected.size)
        return constantTimeEquals(expected, actual)
    }

    fun clearPin() {
        prefs.edit()
            .remove(KEY_PIN_SALT).remove(KEY_PIN_HASH).remove(KEY_PIN_ITER)
            .remove(KEY_LOCKED)
            .apply()
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int, keyLen: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, keyLen * 8)
        val factory = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        } catch (_: Exception) {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        }
        return factory.generateSecret(spec).encoded
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    /* ------------------------------------------------------------------ */
    /*  TUNING-PROFILE                                                     */
    /* ------------------------------------------------------------------ */
    /** Speichert/ueberschreibt ein Profil (Format identisch zum Original). */
    fun saveProfile(profile: ScooterProfile) {
        val entry = encodeProfile(profile)
        val set = prefs.getStringSet(KEY_PROFILES, emptySet())?.toMutableSet() ?: mutableSetOf()
        // FIX: Namen ueber denselben Parser bestimmen (nicht substringBefore('|')),
        // damit ein Profilname mit '|' beim Ueberschreiben korrekt erkannt wird.
        set.removeAll { decodeProfile(it)?.name == profile.name }
        set.add(entry)
        prefs.edit().putStringSet(KEY_PROFILES, set).apply()
    }

    /**
     * Alle gespeicherten Profil-Eintraege (roh, alphabetisch sortiert).
     * Format je Eintrag: "name|maxSpeed|zeroStart|kers|cruise" — identisch zum
     * Original, damit die UI-Darstellung unveraendert bleibt.
     */
    fun profileEntries(): List<String> =
        prefs.getStringSet(KEY_PROFILES, emptySet())?.sorted() ?: emptyList()

    /**
     * Laedt ein Profil aus einem rohen Eintrag (null, wenn ungueltig).
     *
     * FIX: Verwendet bewusst denselben Parser wie [saveProfile]/[decodeProfile].
     * Zuvor nutzte [loadProfile] einen naiven `split("|")` mit `parts[0]` als
     * Namen. Ein Profilname mit '|' wurde dadurch beim SPEICHERN/UEBERSCHREIBEN
     * korrekt behandelt, beim LADEN aber falsch (Name abgeschnitten, Zahlenfelder
     * verschoben) bzw. ganz verworfen. Jetzt gibt es genau EINE Parsing-Quelle.
     */
    fun loadProfile(entry: String): ScooterProfile? = decodeProfile(entry)

    companion object {
        /**
         * Serialisiert ein Profil. Format bleibt "name|maxSpeed|zeroStart|kers|cruise".
         * Der Name steht vorn und darf selbst '|' enthalten.
         */
        fun encodeProfile(profile: ScooterProfile): String =
            "${profile.name}|${profile.maxSpeed}|${profile.zeroStart}|${profile.kers}|${profile.cruiseControl}"

        /**
         * FIX: Parst von RECHTS. Die letzten vier Felder sind immer
         * maxSpeed|zeroStart|kers|cruise; alles davor ist der Name. Dadurch
         * verschiebt ein '|' im Namen die Zahlenfelder nicht mehr.
         * Abwaertskompatibel zu allen alten Eintraegen (Name ohne '|').
         */
        fun decodeProfile(entry: String): ScooterProfile? {
            val parts = entry.split("|")
            if (parts.size < 5) return null
            val name = parts.subList(0, parts.size - 4).joinToString("|")
            return ScooterProfile(
                name = name,
                maxSpeed = parts[parts.size - 4].toIntOrNull() ?: return null,
                zeroStart = parts[parts.size - 3].toIntOrNull() ?: 0,
                kers = parts[parts.size - 2].toIntOrNull() ?: 1,
                cruiseControl = parts[parts.size - 1].toBoolean()
            )
        }

        private const val PREFS_NAME = "SyrakPrefs"

        private const val KEY_DISCLAIMERS = "disclaimers_accepted"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_REGION = "region_code"
        private const val KEY_PROTOCOL = "last_protocol"
        private const val KEY_ADAPTER_ID = "last_adapter_id"
        private const val KEY_LOCKED = "is_locked"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_ITER = "pin_iterations"
        private const val KEY_PROFILES = "profiles"

        private const val SALT_BYTES = 16
        private const val ITERATIONS = 120_000
        private const val KEY_LENGTH_BYTES = 32
    }
}
