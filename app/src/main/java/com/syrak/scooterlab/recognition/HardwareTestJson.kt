package com.syrak.scooterlab.recognition

import com.syrak.scooterlab.ScooterManufacturer
import com.syrak.scooterlab.diagnostics.GattProperty

/* ============================================================================
 *  PHASE 17.4 — JSON-FORMAT FUER HARDWARE-TESTDATENSAETZE
 *  ---------------------------------------------------------------------------
 *  Bewusst OHNE neue Bibliothek: ein kleiner, vollstaendig testbarer
 *  JSON-Parser/Writer in reinem Kotlin. Damit bleibt das Modul unabhaengig von
 *  Android und von kotlinx.serialization.
 *
 *  FORMAT (Schema-Version 1):
 *  {
 *    "schemaVersion": 1,
 *    "generatedAt": "yyyy-MM-dd",       // optional, nur Information
 *    "recordCount": 2,
 *    "records": [
 *      {
 *        "id": "...", "manufacturer": "NAVEE", "modelName": "...",
 *        "testDate": "2026-11-15", "tester": "...", "addressMasked": "AA:BB:**:**:EE:FF",
 *        "expectedFingerprintId": "navee-gt3max-unconfirmed",
 *        "scan": { "deviceName": "...", "serviceUuids": [], "manufacturerIds": [],
 *                  "serviceDataUuids": [], "rssi": -55 },
 *        "gatt": { "discoveryStatus": "COMPLETE",
 *                  "services": [ { "uuid": "...", "label": "",
 *                                  "characteristics": [ { "uuid": "...",
 *                                    "properties": ["READ","NOTIFY"],
 *                                    "userDescription": null } ] } ],
 *                  "errors": [] },
 *        "observedRecognition": { "confidence": "PROBABLE",
 *                  "detectedBrand": "NAVEE", "detectedBrandFamily": "NAVEE",
 *                  "detectedModelFamily": "NAVEE", "confirmedModel": null,
 *                  "probableModel": "...", "matchedProfileId": "..." },
 *        "notes": "..."
 *      }
 *    ]
 *  }
 *
 *  DATENSCHUTZ: MAC-Adressen werden beim Export UND beim Import maskiert.
 *  Seriennummern sind kein Feld dieses Formats und werden nicht geschrieben.
 * ==========================================================================*/

class HardwareTestJsonException(message: String) : Exception(message)

/* ------------------------------- JSON-Werte ------------------------------- */

internal sealed class JsonValue
internal data class JsonObject(val values: Map<String, JsonValue>) : JsonValue()
internal data class JsonArray(val values: List<JsonValue>) : JsonValue()
internal data class JsonString(val value: String) : JsonValue()
internal data class JsonNumber(val value: Double, val raw: String) : JsonValue()
internal data class JsonBoolean(val value: Boolean) : JsonValue()
internal object JsonNull : JsonValue()

/** Kleiner rekursiver JSON-Parser mit klaren Fehlermeldungen. */
internal class JsonReader(private val text: String) {

    private var pos = 0

    fun parse(): JsonValue {
        skipWhitespace()
        val value = readValue()
        skipWhitespace()
        if (pos != text.length) fail("Unerwartete Zeichen nach dem JSON-Objekt")
        return value
    }

    private fun readValue(): JsonValue {
        if (pos >= text.length) fail("Unerwartetes Dateiende")
        return when (val c = text[pos]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> JsonString(readString())
            't' -> readLiteral("true", JsonBoolean(true))
            'f' -> readLiteral("false", JsonBoolean(false))
            'n' -> readLiteral("null", JsonNull)
            else -> if (c == '-' || c.isDigit()) readNumber() else fail("Unerwartetes Zeichen '$c'")
        }
    }

    private fun readObject(): JsonObject {
        expect('{')
        val map = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (peek() == '}') { pos++; return JsonObject(map) }
        while (true) {
            skipWhitespace()
            val key = readString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            map[key] = readValue()
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                '}' -> { pos++; return JsonObject(map) }
                else -> fail("Erwartet ',' oder '}'")
            }
        }
    }

    private fun readArray(): JsonArray {
        expect('[')
        val list = mutableListOf<JsonValue>()
        skipWhitespace()
        if (peek() == ']') { pos++; return JsonArray(list) }
        while (true) {
            skipWhitespace()
            list += readValue()
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                ']' -> { pos++; return JsonArray(list) }
                else -> fail("Erwartet ',' oder ']'")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (pos >= text.length) fail("Nicht beendeter String")
            when (val c = text[pos++]) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= text.length) fail("Nicht beendeter Escape")
                    when (val e = text[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > text.length) fail("Unvollstaendiges \\u-Escape")
                            val hex = text.substring(pos, pos + 4)
                            val code = hex.toIntOrNull(16) ?: fail("Ungueltiges \\u-Escape")
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> fail("Unbekanntes Escape '\\$e'")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun readNumber(): JsonNumber {
        val start = pos
        if (peek() == '-') pos++
        while (pos < text.length && text[pos].isDigit()) pos++
        if (pos < text.length && text[pos] == '.') {
            pos++
            while (pos < text.length && text[pos].isDigit()) pos++
        }
        if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
            pos++
            if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
            while (pos < text.length && text[pos].isDigit()) pos++
        }
        val raw = text.substring(start, pos)
        val number = raw.toDoubleOrNull() ?: fail("Ungueltige Zahl '$raw'")
        return JsonNumber(number, raw)
    }

    private fun readLiteral(literal: String, value: JsonValue): JsonValue {
        if (!text.startsWith(literal, pos)) fail("Ungueltiges Literal")
        pos += literal.length
        return value
    }

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun peek(): Char = if (pos < text.length) text[pos] else '\u0000'

    private fun expect(c: Char) {
        if (peek() != c) fail("Erwartet '$c'")
        pos++
    }

    private fun fail(message: String): Nothing =
        throw HardwareTestJsonException("$message (Position $pos)")
}

/** Serialisiert JSON-Werte stabil und ohne externe Abhaengigkeit. */
internal object JsonWriter {

    fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    fun stringOrNull(value: String?): String = if (value == null) "null" else quote(value)

    fun array(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]", separator = ",") { quote(it) }

    fun intArray(values: List<Int>): String =
        values.joinToString(prefix = "[", postfix = "]", separator = ",") { it.toString() }
}

/* ------------------------------- Codec ------------------------------------ */

data class HardwareTestDecodeResult(
    val records: List<HardwareTestRecord> = emptyList(),
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
) {
    val isSuccess: Boolean get() = errors.isEmpty()
}

object HardwareTestJson {

    const val SCHEMA_VERSION = 1
    const val SCHEMA_KEY = "schemaVersion"
    const val RECORDS_KEY = "records"

    /* ------------------------------ Export ------------------------------ */

    /**
     * Serialisiert Datensaetze. MAC-Adressen werden erneut maskiert (auch wenn
     * ein Datensatz versehentlich eine Roh-MAC enthalten sollte).
     */
    fun encode(records: List<HardwareTestRecord>, generatedAt: String? = null): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"schemaVersion\":").append(SCHEMA_VERSION).append(',')
        if (generatedAt != null) {
            sb.append("\"generatedAt\":").append(JsonWriter.quote(generatedAt)).append(',')
        }
        sb.append("\"recordCount\":").append(records.size).append(',')
        sb.append("\"records\":[")
        records.forEachIndexed { index, record ->
            if (index > 0) sb.append(',')
            sb.append(encodeRecord(record))
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun encodeRecord(record: HardwareTestRecord): String = buildString {
        append('{')
        append("\"id\":").append(JsonWriter.quote(record.id)).append(',')
        append("\"manufacturer\":").append(JsonWriter.quote(record.manufacturer.name)).append(',')
        append("\"modelName\":").append(JsonWriter.quote(record.modelName)).append(',')
        append("\"testDate\":").append(JsonWriter.quote(record.testDate)).append(',')
        append("\"tester\":").append(JsonWriter.stringOrNull(record.tester)).append(',')
        append("\"addressMasked\":").append(JsonWriter.stringOrNull(maskAddress(record.addressMasked))).append(',')
        append("\"expectedFingerprintId\":").append(JsonWriter.stringOrNull(record.expectedFingerprintId)).append(',')
        append("\"scan\":").append(record.scan?.let(::encodeScan) ?: "null").append(',')
        append("\"gatt\":").append(record.gatt?.let(::encodeGatt) ?: "null").append(',')
        append("\"observedRecognition\":")
            .append(record.observedRecognition?.let(::encodeRecognition) ?: "null").append(',')
        append("\"notes\":").append(JsonWriter.stringOrNull(record.notes))
        append('}')
    }

    private fun encodeScan(scan: HardwareScanSnapshot): String = buildString {
        append('{')
        append("\"deviceName\":").append(JsonWriter.stringOrNull(scan.deviceName)).append(',')
        append("\"serviceUuids\":").append(JsonWriter.array(scan.serviceUuids)).append(',')
        append("\"manufacturerIds\":").append(JsonWriter.intArray(scan.manufacturerIds)).append(',')
        append("\"serviceDataUuids\":").append(JsonWriter.array(scan.serviceDataUuids)).append(',')
        append("\"rssi\":").append(scan.rssi?.toString() ?: "null")
        append('}')
    }

    private fun encodeGatt(gatt: HardwareGattSnapshot): String = buildString {
        append('{')
        append("\"discoveryStatus\":").append(JsonWriter.quote(gatt.discoveryStatus.name)).append(',')
        append("\"services\":[")
        gatt.services.forEachIndexed { index, service ->
            if (index > 0) append(',')
            append('{')
            append("\"uuid\":").append(JsonWriter.quote(service.uuid)).append(',')
            append("\"label\":").append(JsonWriter.quote(service.label)).append(',')
            append("\"characteristics\":[")
            service.characteristics.forEachIndexed { cIndex, ch ->
                if (cIndex > 0) append(',')
                append('{')
                append("\"uuid\":").append(JsonWriter.quote(ch.uuid)).append(',')
                append("\"properties\":").append(
                    JsonWriter.array(ch.properties.sortedBy { it.ordinal }.map { it.name })
                ).append(',')
                append("\"userDescription\":").append(JsonWriter.stringOrNull(ch.userDescription))
                append('}')
            }
            append("]}")
        }
        append("],")
        append("\"errors\":").append(JsonWriter.array(gatt.errors))
        append('}')
    }

    private fun encodeRecognition(rec: HardwareRecognitionSnapshot): String = buildString {
        append('{')
        append("\"confidence\":").append(JsonWriter.quote(rec.confidence.name)).append(',')
        append("\"detectedBrand\":").append(JsonWriter.quote(rec.detectedBrand.name)).append(',')
        append("\"detectedBrandFamily\":").append(JsonWriter.quote(rec.detectedBrandFamily.name)).append(',')
        append("\"detectedModelFamily\":").append(JsonWriter.stringOrNull(rec.detectedModelFamily)).append(',')
        append("\"confirmedModel\":").append(JsonWriter.stringOrNull(rec.confirmedModel)).append(',')
        append("\"probableModel\":").append(JsonWriter.stringOrNull(rec.probableModel)).append(',')
        append("\"matchedProfileId\":").append(JsonWriter.stringOrNull(rec.matchedProfileId))
        append('}')
    }

    /* ------------------------------ Import ------------------------------ */

    /**
     * Parst und validiert eine JSON-Datei vollstaendig.
     *
     * Ein struktureller Fehler (kein JSON, falsche Schema-Version, fehlendes
     * `records`-Array) fuehrt zu `errors` und liefert KEINE Datensaetze.
     * Einzelne ungueltige Datensaetze werden abgelehnt, die uebrigen bleiben
     * nutzbar; der Aufrufer entscheidet, ob er sie uebernimmt.
     */
    fun decode(text: String): HardwareTestDecodeResult {
        val root = try {
            JsonReader(text).parse()
        } catch (e: HardwareTestJsonException) {
            return HardwareTestDecodeResult(errors = listOf("JSON ungueltig: ${e.message}"))
        }
        val obj = root as? JsonObject
            ?: return HardwareTestDecodeResult(errors = listOf("Wurzel muss ein JSON-Objekt sein"))

        val schema = obj.values[SCHEMA_KEY]?.asIntOrNull()
            ?: return HardwareTestDecodeResult(errors = listOf("Pflichtfeld 'schemaVersion' fehlt oder ist keine Zahl"))
        if (schema != SCHEMA_VERSION) {
            return HardwareTestDecodeResult(
                errors = listOf("Unbekannte Schema-Version $schema (unterstuetzt: $SCHEMA_VERSION)")
            )
        }

        val recordsValue = obj.values[RECORDS_KEY]
            ?: return HardwareTestDecodeResult(errors = listOf("Pflichtfeld 'records' fehlt"))
        val recordsArray = recordsValue as? JsonArray
            ?: return HardwareTestDecodeResult(errors = listOf("'records' muss ein Array sein"))

        val accepted = mutableListOf<HardwareTestRecord>()
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val seenIds = mutableSetOf<String>()

        recordsArray.values.forEachIndexed { index, value ->
            val recordObj = value as? JsonObject
            if (recordObj == null) {
                errors += "Datensatz #${index + 1}: kein JSON-Objekt"
                return@forEachIndexed
            }
            try {
                val record = decodeRecord(recordObj)
                if (!seenIds.add(record.id)) {
                    errors += "Datensatz #${index + 1}: doppelte ID '${record.id}' in der Datei"
                } else {
                    accepted += record
                }
            } catch (e: HardwareTestJsonException) {
                errors += "Datensatz #${index + 1}: ${e.message}"
            }
        }

        if (recordsArray.values.isEmpty()) {
            warnings += "Die Datei enthaelt keine Datensaetze."
        }
        return HardwareTestDecodeResult(accepted, errors, warnings)
    }

    private fun decodeRecord(obj: JsonObject): HardwareTestRecord {
        val id = obj.requiredString("id")
        if (id.isBlank()) throw HardwareTestJsonException("'id' darf nicht leer sein")

        val manufacturerName = obj.requiredString("manufacturer")
        val manufacturer = enumOrNull<ScooterManufacturer>(manufacturerName)
            ?: throw HardwareTestJsonException("unbekannter Hersteller '$manufacturerName'")

        val modelName = obj.requiredString("modelName")
        if (modelName.isBlank()) throw HardwareTestJsonException("'modelName' darf nicht leer sein")

        val testDate = obj.requiredString("testDate")
        if (!IsoDate.isValid(testDate)) {
            throw HardwareTestJsonException("ungueltiges Testdatum '$testDate' (erwartet yyyy-MM-dd)")
        }

        val fingerprintId = obj.optionalString("expectedFingerprintId")
        if (!fingerprintId.isNullOrBlank() && BuiltInModelFingerprints.byId(fingerprintId) == null) {
            throw HardwareTestJsonException("unbekannter Fingerprint '$fingerprintId'")
        }

        val scan = obj.values["scan"]?.let { value ->
            if (value is JsonNull) null else decodeScan(value.asObjectOrThrow("scan"))
        }
        val gatt = obj.values["gatt"]?.let { value ->
            if (value is JsonNull) null else decodeGatt(value.asObjectOrThrow("gatt"))
        }
        val observed = obj.values["observedRecognition"]?.let { value ->
            if (value is JsonNull) null else decodeRecognition(value.asObjectOrThrow("observedRecognition"))
        }

        return HardwareTestRecord(
            id = id,
            manufacturer = manufacturer,
            modelName = modelName,
            testDate = testDate,
            tester = obj.optionalString("tester"),
            addressMasked = maskAddress(obj.optionalString("addressMasked")),
            expectedFingerprintId = fingerprintId,
            scan = scan,
            gatt = gatt,
            observedRecognition = observed,
            notes = obj.optionalString("notes")
        )
    }

    private fun decodeScan(obj: JsonObject): HardwareScanSnapshot = HardwareScanSnapshot(
        deviceName = obj.optionalString("deviceName"),
        serviceUuids = obj.stringList("serviceUuids"),
        manufacturerIds = obj.intList("manufacturerIds"),
        serviceDataUuids = obj.stringList("serviceDataUuids"),
        rssi = obj.values["rssi"]?.asIntOrNull()
    )

    private fun decodeGatt(obj: JsonObject): HardwareGattSnapshot {
        val statusName = obj.requiredString("discoveryStatus")
        val status = enumOrNull<GattDiscoveryStatus>(statusName)
            ?: throw HardwareTestJsonException("unbekannter Discovery-Status '$statusName'")
        val servicesValue = obj.values["services"]
        val services = if (servicesValue == null || servicesValue is JsonNull) {
            emptyList()
        } else {
            (servicesValue as? JsonArray
                ?: throw HardwareTestJsonException("'gatt.services' muss ein Array sein")).values.mapIndexed { i, v ->
                val serviceObj = v.asObjectOrThrow("gatt.services[$i]")
                val uuid = serviceObj.requiredString("uuid")
                if (uuid.isBlank()) throw HardwareTestJsonException("Service #${i + 1}: UUID fehlt")
                val charsValue = serviceObj.values["characteristics"]
                val chars = if (charsValue == null || charsValue is JsonNull) {
                    emptyList()
                } else {
                    (charsValue as? JsonArray
                        ?: throw HardwareTestJsonException("'characteristics' muss ein Array sein"))
                        .values.mapIndexed { cIndex, cv ->
                            val chObj = cv.asObjectOrThrow("characteristic[$cIndex]")
                            val chUuid = chObj.requiredString("uuid")
                            if (chUuid.isBlank()) {
                                throw HardwareTestJsonException("Characteristic #${cIndex + 1}: UUID fehlt")
                            }
                            val props = chObj.stringList("properties").map { name ->
                                enumOrNull<GattProperty>(name)
                                    ?: throw HardwareTestJsonException("unbekannte GATT-Property '$name'")
                            }.toSet()
                            GattCharacteristicFeature(
                                uuid = chUuid,
                                properties = props,
                                userDescription = chObj.optionalString("userDescription")
                            )
                        }
                }
                GattServiceFeature(
                    uuid = uuid,
                    label = serviceObj.optionalString("label") ?: "",
                    characteristics = chars
                )
            }
        }
        return HardwareGattSnapshot(
            discoveryStatus = status,
            services = services,
            errors = obj.stringList("errors")
        )
    }

    private fun decodeRecognition(obj: JsonObject): HardwareRecognitionSnapshot {
        val confidence = enumOrNull<RecognitionConfidence>(obj.requiredString("confidence"))
            ?: throw HardwareTestJsonException("unbekannte Konfidenz")
        val brand = enumOrNull<ScooterManufacturer>(obj.requiredString("detectedBrand"))
            ?: throw HardwareTestJsonException("unbekannte erkannte Marke")
        val family = enumOrNull<BrandFamily>(obj.requiredString("detectedBrandFamily"))
            ?: throw HardwareTestJsonException("unbekannte Markenfamilie")
        return HardwareRecognitionSnapshot(
            confidence = confidence,
            detectedBrand = brand,
            detectedBrandFamily = family,
            detectedModelFamily = obj.optionalString("detectedModelFamily"),
            confirmedModel = obj.optionalString("confirmedModel"),
            probableModel = obj.optionalString("probableModel"),
            matchedProfileId = obj.optionalString("matchedProfileId")
        )
    }

    /* ------------------------------ Helfer ------------------------------ */

    /**
     * Maskiert eine MAC-Adresse. Nicht-MAC-Werte werden verworfen (null),
     * damit niemals eine unerkannte Rohkennung exportiert wird.
     */
    fun maskAddress(value: String?): String? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val masked = RecognitionExport.maskAddress(text)
        return masked.takeIf { it != "\u2014" }
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }

    private fun JsonValue.asObjectOrThrow(field: String): JsonObject =
        this as? JsonObject ?: throw HardwareTestJsonException("'$field' muss ein Objekt sein")

    private fun JsonValue.asIntOrNull(): Int? = when (this) {
        is JsonNumber -> value.toInt()
        is JsonString -> value.toIntOrNull()
        else -> null
    }

    private fun JsonObject.requiredString(key: String): String {
        val value = values[key] ?: throw HardwareTestJsonException("Pflichtfeld '$key' fehlt")
        return (value as? JsonString)?.value
            ?: throw HardwareTestJsonException("'$key' muss ein String sein")
    }

    private fun JsonObject.optionalString(key: String): String? = when (val value = values[key]) {
        null, JsonNull -> null
        is JsonString -> value.value
        else -> throw HardwareTestJsonException("'$key' muss ein String oder null sein")
    }

    private fun JsonObject.stringList(key: String): List<String> {
        val value = values[key] ?: return emptyList()
        if (value is JsonNull) return emptyList()
        val array = value as? JsonArray
            ?: throw HardwareTestJsonException("'$key' muss ein Array sein")
        return array.values.map {
            (it as? JsonString)?.value
                ?: throw HardwareTestJsonException("'$key' darf nur Strings enthalten")
        }
    }

    private fun JsonObject.intList(key: String): List<Int> {
        val value = values[key] ?: return emptyList()
        if (value is JsonNull) return emptyList()
        val array = value as? JsonArray
            ?: throw HardwareTestJsonException("'$key' muss ein Array sein")
        return array.values.map {
            it.asIntOrNull() ?: throw HardwareTestJsonException("'$key' darf nur Zahlen enthalten")
        }
    }
}
