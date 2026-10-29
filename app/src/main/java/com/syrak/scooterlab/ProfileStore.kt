package com.syrak.scooterlab

/* ============================================================================
 *  PROFILE-STORE  (reine Logik, ohne Android-Abhaengigkeiten)
 *  ---------------------------------------------------------------------------
 *  Die Persistenz der Tuning-Profile lag bisher direkt in [ScooterRepository]
 *  und war dadurch nur mit einem echten Android-Context testbar. Dieses Objekt
 *  enthaelt NUR die Format- und Mengenlogik:
 *
 *    • encode/decode eines Eintrags  ("name|maxSpeed|zeroStart|kers|cruise")
 *    • Upsert (Speichern bzw. Ueberschreiben ueber den Namen)
 *    • Entfernen ueber den Namen
 *    • Auslesen aller GUELTIGEN Profile
 *    • Zaehlen der ungueltigen Eintraege
 *
 *  [ScooterRepository] bleibt die einzige Stelle, die SharedPreferences
 *  anfasst; es delegiert die Logik hierher. Damit ist der komplette
 *  Speichern/Laden/Ueberschreiben/Loeschen-Pfad als Unit-Test pruefbar.
 *
 *  FORMAT: unveraendert zum Original. Der Name steht vorn und darf selbst '|'
 *  enthalten; geparst wird deshalb von RECHTS (die letzten vier Felder sind
 *  immer maxSpeed|zeroStart|kers|cruise).
 *
 *  WICHTIG: Ungueltige Eintraege werden NIE stillschweigend geloescht. Sie
 *  bleiben im Speicher erhalten, werden beim Auslesen uebersprungen und ueber
 *  [invalidCount] sichtbar gemacht. Ein beschaedigter Eintrag kann so nie
 *  andere, gueltige Profile beschaedigen.
 * ==========================================================================*/
object ProfileStore {

    /** Anzahl der Felder hinter dem Namen (maxSpeed, zeroStart, kers, cruise). */
    private const val TRAILING_FIELDS = 4

    /** Serialisiert ein Profil. Format bleibt "name|maxSpeed|zeroStart|kers|cruise". */
    fun encode(profile: ScooterProfile): String =
        "${profile.name}|${profile.maxSpeed}|${profile.zeroStart}|${profile.kers}|${profile.cruiseControl}"

    /**
     * Parst einen rohen Eintrag. Liefert null, wenn der Eintrag unbrauchbar ist
     * (zu wenige Felder, ungueltige maxSpeed).
     *
     * Ein LEERER Name wird bewusst NICHT abgelehnt: encode/decode muss
     * verlustfrei bleiben (siehe ProfileSerializationTest). Die Regel "kein
     * leeres Profil anlegen" wird dort durchgesetzt, wo Profile entstehen —
     * in der UI ([isValidName]) — und nicht im Parser.
     */
    fun decode(entry: String): ScooterProfile? {
        val parts = entry.split("|")
        if (parts.size < TRAILING_FIELDS + 1) return null
        val name = parts.subList(0, parts.size - TRAILING_FIELDS).joinToString("|")
        val maxSpeed = parts[parts.size - 4].trim().toIntOrNull() ?: return null
        val zeroStart = parts[parts.size - 3].trim().toIntOrNull() ?: 0
        val kers = parts[parts.size - 2].trim().toIntOrNull() ?: 1
        // toBoolean() ist absichtlich tolerant: ein unbekannter Wert wird als
        // "aus" gelesen, statt das ganze Profil zu verwerfen.
        val cruise = parts[parts.size - 1].trim().toBoolean()
        return ScooterProfile(
            name = name,
            maxSpeed = maxSpeed,
            zeroStart = zeroStart,
            kers = kers,
            cruiseControl = cruise
        )
    }

    /**
     * Speichert [profile] in [existing] und ueberschreibt dabei ein vorhandenes
     * Profil mit demselben Namen. Ungueltige Alt-Eintraege bleiben unberuehrt.
     */
    fun upsert(existing: Collection<String>, profile: ScooterProfile): Set<String> {
        val result = existing.toMutableSet()
        // Ueberschreiben ueber den GEPARSTEN Namen (nicht per substringBefore),
        // damit ein Name mit '|' korrekt erkannt wird.
        result.removeAll { decode(it)?.name == profile.name }
        result.add(encode(profile))
        return result
    }

    /**
     * Entfernt das Profil mit [name]. Eintraege, die nicht parsebar sind, werden
     * NICHT entfernt (sie koennten zu einem anderen Profil gehoeren).
     */
    fun remove(existing: Collection<String>, name: String): Set<String> {
        val result = existing.toMutableSet()
        result.removeAll { decode(it)?.name == name }
        return result
    }

    /** Alle gueltigen Profile, alphabetisch nach Name sortiert. */
    fun decodeAll(existing: Collection<String>): List<ScooterProfile> =
        existing.mapNotNull { decode(it) }.sortedBy { it.name.lowercase() }

    /** Anzahl der Eintraege, die [decode] nicht verarbeiten kann. */
    fun invalidCount(existing: Collection<String>): Int =
        existing.count { decode(it) == null }

    /** true, wenn bereits ein Profil mit [name] existiert. */
    fun contains(existing: Collection<String>, name: String): Boolean =
        existing.any { decode(it)?.name == name }

    /**
     * Regel fuer NEU anzulegende Profile: der Name darf nicht leer sein.
     * Ein Profil ohne Namen waere in der UI nicht sinnvoll ansprechbar.
     * Bereits gespeicherte Eintraege werden davon NICHT angetastet.
     */
    fun isValidName(name: String): Boolean = name.isNotBlank()
}
