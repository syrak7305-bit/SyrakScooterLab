package com.syrak.scooterlab

/* ============================================================================
 *  PHASE 16 — ZENTRALE UUID-REGISTRY MIT HERKUNFT (reine Logik, unit-testbar)
 *  ---------------------------------------------------------------------------
 *  WARUM DIESE DATEI EXISTIERT (belegter Widerspruch im Projekt):
 *
 *  Vor Phase 16 existierten ZWEI parallele BLE-Stacks mit UNTERSCHIEDLICHEN
 *  UUID-Definitionen:
 *
 *    (A) `core.ble.BleConstants` (Legacy-/Compose-Pfad, benutzt von
 *        `BleManager`) deklarierte den Ninebot-Transport als Nordic-UART
 *        `6e400001/6e400002/6e400003` plus die Xiaomi-Service `fe95`.
 *
 *    (B) `BleEngine` + `NinebotAdapter` (der AKTIVE, von `MainActivity`
 *        benutzte Stack) deklarieren fuer Ninebot `0000e003` mit den
 *        Characteristics `0000fff1` / `0000fff2` und fuer Navee/Brightway
 *        `0000ffe0` / `0000ffe1` / `0000ffe2`.
 *
 *  BEIDE Definitionen sind im Code vorhanden. Der Widerspruch ist damit
 *  erklaert: es sind zwei Generationen desselben Projekts, nicht zwei
 *  Messungen desselben Geraets.
 *
 *  WAS HIER PASSIERT (und was ausdruecklich NICHT):
 *   • Es wird KEINE UUID erfunden und KEINE UUID geaendert. Jeder Wert unten
 *     ist exakt der Wert, der vorher schon im Code stand.
 *   • Es gibt ab jetzt GENAU EINE Textdefinition pro UUID. `BleConstants`,
 *     `BleEngine`, `NinebotAdapter` und `NaveeGt3MaxAdapter` verweisen auf
 *     diese Konstanten — damit kann kein Wert mehr auseinanderlaufen.
 *   • Jede UUID traegt eine HERKUNFT (`UuidProvenance`) und einen Hinweis, ob
 *     sie an realer Hardware bestaetigt wurde. Nichts wird als "bestätigt"
 *     markiert, was nicht bestaetigt ist.
 *
 *  OFFENE FRAGE (ehrlich, nicht behoben):
 *   Welcher Transport am Navee GT3 Max bzw. an einer konkreten Ninebot-
 *   Firmware tatsaechlich aktiv ist, laesst sich ohne echten GATT-Mitschnitt
 *   nicht entscheiden. Deshalb bleibt `NINEBOT_SERVICE` als
 *   `PROJECT_CONSTANT` (nicht `DOCUMENTED`) markiert und der Legacy-UART-
 *   Pfad als `LEGACY_UNCONFIRMED`. Die App probiert beide Pfade NICHT
 *   blindlings parallel aus — sie liest ausschliesslich den real vom Geraet
 *   gemeldeten GATT-Baum.
 * ==========================================================================*/

/**
 * Herkunft / Belegstatus einer UUID-Definition.
 *
 * Bewusst konservativ sortiert: nur [DOCUMENTED] gilt als extern belegt.
 */
enum class UuidProvenance {
    /** Aus oeffentlich dokumentierter, nachpruefbarer Quelle (z. B. Bluetooth-SIG). */
    DOCUMENTED,

    /** Im Projekt vorhandene Konstante; Quelle nicht extern nachpruefbar. */
    PROJECT_CONSTANT,

    /** Aus einem frueheren Projektstand uebernommen, aber nicht mehr im aktiven Pfad. */
    LEGACY_UNCONFIRMED,

    /** Platzhalter / reine Annahme. Darf NIE als "unterstuetzt" gelten. */
    PLACEHOLDER;

    val isExternallyProven: Boolean get() = this == DOCUMENTED
}

/** Rolle einer UUID im Protokoll-Stack. */
enum class UuidRole {
    GATT_SERVICE,
    WRITE_CHARACTERISTIC,
    NOTIFY_CHARACTERISTIC,
    DESCRIPTOR
}

/**
 * Eine einzelne UUID-Definition inklusive Herkunft und tatsaechlicher Nutzung.
 *
 * @param key          stabiler, eindeutiger Schluessel (fuer Tests/Anzeige)
 * @param uuid         UUID als Text — exakt der bisherige Projektwert
 * @param role         Rolle im GATT-Modell
 * @param family       Protokollfamilie / Herstellerkontext
 * @param provenance   Belegstatus
 * @param sourceNote   woher der Wert stammt (menschenlesbar)
 * @param usedBy       welche Codepfade diese UUID tatsaechlich verwenden
 * @param confirmedOnHardware true NUR nach realem Geraetetest
 */
data class UuidDefinition(
    val key: String,
    val uuid: String,
    val role: UuidRole,
    val family: String,
    val provenance: UuidProvenance,
    val sourceNote: String,
    val usedBy: List<String>,
    val confirmedOnHardware: Boolean = false,
    /**
     * Alle Rollen, fuer die diese UUID im Projekt als Kandidat gefuehrt wird.
     * Enthaelt [role]. Mehr als eine Rolle bedeutet: die tatsaechliche
     * Hardware-Rolle ist NICHT belegt (z. B. Ninebot FFF1/FFF2). Dadurch kann
     * ein Adapter keine Rolle behaupten, die die Registry nicht kennt.
     */
    val candidateRoles: Set<UuidRole> = setOf(role)
) {
    /** true, wenn die Rolle ohne echten GATT-Mitschnitt nicht entscheidbar ist. */
    val roleAmbiguous: Boolean get() = candidateRoles.size > 1

    /** Kurzzeile fuer die Entwickleransicht. */
    fun summary(): String =
        "$uuid · $family · ${role.name} · ${provenance.name}" +
            if (confirmedOnHardware) " · am Gerät bestätigt" else " · NICHT am Gerät bestätigt"
}

object BleUuids {

    /* ------------------------------------------------------------------ */
    /*  Bluetooth-SIG-Standard: Client Characteristic Configuration        */
    /* ------------------------------------------------------------------ */
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"

    /* ------------------------------------------------------------------ */
    /*  Navee / Brightway (FFE0/FFE1/FFE2) — aktiver Pfad in BleEngine     */
    /* ------------------------------------------------------------------ */
    const val NAVEE_SERVICE = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val NAVEE_CHAR_MAIN = "0000ffe1-0000-1000-8000-00805f9b34fb"
    const val NAVEE_CHAR_ALT = "0000ffe2-0000-1000-8000-00805f9b34fb"

    /* ------------------------------------------------------------------ */
    /*  Ninebot / Xiaomi (E003 + FFF1/FFF2) — aktiver Pfad in BleEngine    */
    /* ------------------------------------------------------------------ */
    const val NINEBOT_SERVICE = "0000e003-0000-1000-8000-00805f9b34fb"
    const val NINEBOT_CHAR_WRITE = "0000fff1-0000-1000-8000-00805f9b34fb"
    const val NINEBOT_CHAR_WRITE_ALT = "0000fff2-0000-1000-8000-00805f9b34fb"

    /* ------------------------------------------------------------------ */
    /*  Legacy-Pfad (nur noch `core.ble.BleManager`, nicht MainActivity)   */
    /* ------------------------------------------------------------------ */
    const val LEGACY_UART_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    const val LEGACY_UART_WRITE = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    const val LEGACY_UART_NOTIFY = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    const val LEGACY_XIAOMI_SERVICE = "0000fe95-0000-1000-8000-00805f9b34fb"

    private val definitions: List<UuidDefinition> = listOf(
        UuidDefinition(
            key = "cccd",
            uuid = CCCD,
            role = UuidRole.DESCRIPTOR,
            family = "Bluetooth SIG",
            provenance = UuidProvenance.DOCUMENTED,
            sourceNote = "Bluetooth-SIG-Standarddescriptor fuer Notify/Indicate (0x2902).",
            usedBy = listOf("BleEngine", "core.ble.BleManager"),
            confirmedOnHardware = false
        ),
        UuidDefinition(
            key = "navee.service",
            uuid = NAVEE_SERVICE,
            role = UuidRole.GATT_SERVICE,
            family = "Navee / Brightway",
            provenance = UuidProvenance.PROJECT_CONSTANT,
            sourceNote = "Projektkonstante NaveeFrames.SERVICE_UUID; kein externer Nachweis im Repo.",
            usedBy = listOf("BleEngine", "NaveeGt3MaxAdapter", "GattCharacteristicSelector")
        ),
        UuidDefinition(
            key = "navee.char.main",
            uuid = NAVEE_CHAR_MAIN,
            role = UuidRole.NOTIFY_CHARACTERISTIC,
            family = "Navee / Brightway",
            provenance = UuidProvenance.PROJECT_CONSTANT,
            sourceNote = "Projektkonstante NaveeFrames.CHAR_UUID (bevorzugt fuer Notify).",
            usedBy = listOf("BleEngine", "NaveeGt3MaxAdapter")
        ),
        UuidDefinition(
            key = "navee.char.alt",
            uuid = NAVEE_CHAR_ALT,
            role = UuidRole.WRITE_CHARACTERISTIC,
            family = "Navee / Brightway",
            provenance = UuidProvenance.PROJECT_CONSTANT,
            sourceNote = "Projektkonstante NaveeFrames.CHAR_WRITE_UUID (bevorzugt fuer Write).",
            usedBy = listOf("BleEngine", "NaveeGt3MaxAdapter")
        ),
        UuidDefinition(
            key = "ninebot.service",
            uuid = NINEBOT_SERVICE,
            role = UuidRole.GATT_SERVICE,
            family = "Ninebot / Xiaomi",
            provenance = UuidProvenance.PROJECT_CONSTANT,
            sourceNote = "Projektkonstante in BleEngine/NinebotAdapter. Widerspricht dem " +
                "Legacy-UART-Pfad in BleConstants; ohne GATT-Mitschnitt nicht entscheidbar.",
            usedBy = listOf("BleEngine", "NinebotAdapter")
        ),
        UuidDefinition(
            key = "ninebot.char.write",
            uuid = NINEBOT_CHAR_WRITE,
            role = UuidRole.WRITE_CHARACTERISTIC,
            family = "Ninebot / Xiaomi",
            provenance = UuidProvenance.PROJECT_CONSTANT,
            sourceNote = "Projektkonstante in BleEngine/NinebotAdapter (Write-Kandidat 1). " +
                "Ob FFF1 zusaetzlich Notify/Indicate traegt, ist ohne GATT-Mitschnitt " +
                "NICHT entschieden; daher als Kandidat fuer beide Rollen gefuehrt.",
            usedBy = listOf("BleEngine", "NinebotAdapter"),
            candidateRoles = setOf(
                UuidRole.WRITE_CHARACTERISTIC,
                UuidRole.NOTIFY_CHARACTERISTIC
            )
        ),
        UuidDefinition(
            key = "ninebot.char.write.alt",
            uuid = NINEBOT_CHAR_WRITE_ALT,
            role = UuidRole.WRITE_CHARACTERISTIC,
            family = "Ninebot / Xiaomi",
            provenance = UuidProvenance.PROJECT_CONSTANT,
            sourceNote = "Projektkonstante in BleEngine/NinebotAdapter (Write-Kandidat 2). " +
                "Ob FFF2 zusaetzlich Notify/Indicate traegt, ist ohne GATT-Mitschnitt " +
                "NICHT entschieden; daher als Kandidat fuer beide Rollen gefuehrt.",
            usedBy = listOf("BleEngine", "NinebotAdapter"),
            candidateRoles = setOf(
                UuidRole.WRITE_CHARACTERISTIC,
                UuidRole.NOTIFY_CHARACTERISTIC
            )
        ),
        UuidDefinition(
            key = "legacy.uart.service",
            uuid = LEGACY_UART_SERVICE,
            role = UuidRole.GATT_SERVICE,
            family = "Nordic UART (Legacy)",
            provenance = UuidProvenance.LEGACY_UNCONFIRMED,
            sourceNote = "Nordic-UART-Layout; oeffentlich fuer Xiaomi M365/1S dokumentiert, " +
                "im Projekt aber nur noch im nicht mehr benutzten BleManager-Pfad.",
            usedBy = listOf("core.ble.BleConstants", "core.ble.BleManager")
        ),
        UuidDefinition(
            key = "legacy.uart.write",
            uuid = LEGACY_UART_WRITE,
            role = UuidRole.WRITE_CHARACTERISTIC,
            family = "Nordic UART (Legacy)",
            provenance = UuidProvenance.LEGACY_UNCONFIRMED,
            sourceNote = "Nordic-UART Write-Characteristic (Legacy-Pfad).",
            usedBy = listOf("core.ble.BleConstants", "core.ble.BleManager")
        ),
        UuidDefinition(
            key = "legacy.uart.notify",
            uuid = LEGACY_UART_NOTIFY,
            role = UuidRole.NOTIFY_CHARACTERISTIC,
            family = "Nordic UART (Legacy)",
            provenance = UuidProvenance.LEGACY_UNCONFIRMED,
            sourceNote = "Nordic-UART Notify-Characteristic (Legacy-Pfad).",
            usedBy = listOf("core.ble.BleConstants", "core.ble.BleManager")
        ),
        UuidDefinition(
            key = "legacy.xiaomi.service",
            uuid = LEGACY_XIAOMI_SERVICE,
            role = UuidRole.GATT_SERVICE,
            family = "Xiaomi (Legacy)",
            provenance = UuidProvenance.LEGACY_UNCONFIRMED,
            sourceNote = "Xiaomi-Service aus dem Legacy-Scanfilter; nicht im aktiven Pfad.",
            usedBy = listOf("core.ble.BleConstants", "core.ble.BleManager")
        )
    )

    /** Alle Definitionen (Reihenfolge = Anzeigereihenfolge). */
    fun all(): List<UuidDefinition> = definitions

    /** Definition zu einer UUID (case-insensitive) oder null. */
    fun definitionOf(uuid: String): UuidDefinition? =
        definitions.firstOrNull { it.uuid.equals(uuid, ignoreCase = true) }

    /** true, wenn die UUID im Projekt ueberhaupt bekannt ist. */
    fun isKnown(uuid: String): Boolean = definitionOf(uuid) != null

    /**
     * Alle UUID-Texte einer Familie, die fuer [role] als Kandidat gefuehrt
     * werden. Adapter leiten ihre Write-/Notify-Listen hieraus ab, damit
     * Registry und Adapter nie auseinanderlaufen koennen.
     */
    fun candidateUuids(family: String, role: UuidRole): List<String> =
        definitions
            .filter { it.family == family && role in it.candidateRoles }
            .map { it.uuid }

    /** Kandidatenrollen einer UUID (leer, wenn unbekannt). */
    fun rolesOf(uuid: String): Set<UuidRole> =
        definitionOf(uuid)?.candidateRoles ?: emptySet()

    /** true, wenn die Rolle einer bekannten UUID nicht eindeutig belegt ist. */
    fun isRoleAmbiguous(uuid: String): Boolean =
        definitionOf(uuid)?.roleAmbiguous == true

    /** Belegstatus einer UUID; unbekannte UUIDs gelten als PLACEHOLDER. */
    fun provenanceOf(uuid: String): UuidProvenance =
        definitionOf(uuid)?.provenance ?: UuidProvenance.PLACEHOLDER

    /** Nur die UUIDs, die der aktive Stack (BleEngine/Adapter) verwendet. */
    fun activeUuids(): List<UuidDefinition> =
        definitions.filter { d -> d.usedBy.any { it == "BleEngine" } }

    /**
     * Prueft, ob derselbe UUID-Text fuer widersprechende Rollen/Familien
     * deklariert ist. Ergebnis ist eine Liste menschenlesbarer Konflikte;
     * leer bedeutet: keine widerspruechlichen Doppeldefinitionen.
     */
    fun conflicts(): List<String> {
        val out = ArrayList<String>()
        // 1) Gleicher Text, unterschiedliche Rolle.
        definitions.groupBy { it.uuid.lowercase() }.forEach { (uuid, defs) ->
            if (defs.map { it.role }.distinct().size > 1) {
                out.add("$uuid ist fuer mehrere Rollen deklariert: " +
                    defs.joinToString { "${it.key}=${it.role}" })
            }
            if (defs.map { it.family }.distinct().size > 1) {
                out.add("$uuid ist fuer mehrere Familien deklariert: " +
                    defs.joinToString { "${it.key}=${it.family}" })
            }
        }
        // 2) Gleicher Key, unterschiedlicher Text (darf nie passieren).
        definitions.groupBy { it.key }.forEach { (key, defs) ->
            if (defs.map { it.uuid.lowercase() }.distinct().size > 1) {
                out.add("Key '$key' hat mehrere UUID-Werte: " +
                    defs.joinToString { it.uuid })
            }
        }
        return out
    }

    /**
     * Belegte Widersprueche zwischen dem aktiven und dem Legacy-Transport.
     * Diese Liste ist ABSICHTLICH nicht leer: sie dokumentiert die offene
     * Protokollfrage, statt sie zu verstecken.
     */
    fun transportContradictions(): List<String> = listOf(
        "Ninebot-Transport: aktiver Pfad nutzt $NINEBOT_SERVICE/$NINEBOT_CHAR_WRITE" +
            "/$NINEBOT_CHAR_WRITE_ALT, der Legacy-Pfad nutzt $LEGACY_UART_SERVICE" +
            "/$LEGACY_UART_WRITE/$LEGACY_UART_NOTIFY.",
        "Beide Varianten sind im Code vorhanden. Welche an einer konkreten Firmware " +
            "aktiv ist, ist ohne echten GATT-Mitschnitt NICHT entschieden.",
        "Keine der beiden Varianten ist am realen Fahrzeug bestaetigt " +
            "(confirmedOnHardware = false)."
    )

    /** Anzahl der UUIDs ohne externen Beleg (fuer die ehrliche UI-Anzeige). */
    fun unprovenCount(): Int =
        definitions.count { !it.provenance.isExternallyProven }

    /** Anzahl der am realen Geraet bestaetigten UUIDs (derzeit bewusst 0). */
    fun confirmedOnHardwareCount(): Int = definitions.count { it.confirmedOnHardware }
}
