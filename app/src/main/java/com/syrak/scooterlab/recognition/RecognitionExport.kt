package com.syrak.scooterlab.recognition

import com.syrak.scooterlab.RiderScooterProfile

/* ============================================================================
 *  PHASE 17.1 — ERKENNUNGS-EXPORT
 *  ---------------------------------------------------------------------------
 *  Additiver, exportierbarer Block fuer den Hardware-Testbericht.
 *
 *  DATENSCHUTZ (bindend):
 *   • Seriennummer wird NICHT exportiert.
 *   • MAC-Adresse wird maskiert (nur erste und letzte zwei Oktette).
 *   • Keine weiteren personenbezogenen Daten.
 *
 *  NUTZERPROFIL und HARDWARE-ERKENNUNG bleiben getrennte Felder. Es wird
 *  nirgends eine Kompatibilitaet behauptet.
 * ==========================================================================*/

data class RecognitionExport(
    // --- Nutzerprofil (vom Nutzer deklariert, NICHT erkannt) ---
    val userProfileBrand: String,
    val userProfileModel: String,
    val userProfileModelCode: String,
    val userProfileModelNotListed: Boolean,
    val userProfileIdentificationConfidence: String,
    // --- Hardware-Erkennung (tatsaechlich beobachtet) ---
    val deviceName: String?,
    val deviceAddressMasked: String,
    val detectedBrand: String,
    val detectedBrandFamily: String,
    val detectedModelFamily: String?,
    val confirmedModel: String?,
    val probableModel: String?,
    val confidence: String,
    val matchedProfileId: String?,
    // --- Advertisement ---
    val advertisementServiceUuids: List<String>,
    val advertisementManufacturerIds: List<String>,
    val advertisementServiceDataUuids: List<String>,
    val advertisementRssi: Int?,
    // --- GATT ---
    val gattDiscoveryStatus: String,
    val gattServiceUuids: List<String>,
    val gattCharacteristicCount: Int,
    val gattCharacteristics: List<String>,
    // --- Begruendung ---
    val reasons: List<String>,
    val matchedFeatures: List<String>,
    val missingFeatures: List<String>,
    val contradictoryFeatures: List<String>,
    val unknownFeatures: List<String>,
    val errors: List<String>
) {
    companion object {

        /** Baut den Exportblock aus einem Ergebnis und dem Nutzerprofil. */
        fun from(result: RecognitionResult, profile: RiderScooterProfile?): RecognitionExport {
            val adv = result.advertisement
            val gatt = result.gatt
            return RecognitionExport(
                userProfileBrand = profile?.brand?.displayName ?: "—",
                userProfileModel = when {
                    profile == null -> "—"
                    profile.modelNotListed -> "nicht gelistet"
                    else -> profile.model.displayName
                },
                userProfileModelCode = profile?.modelCode?.takeIf { it.isNotBlank() } ?: "—",
                userProfileModelNotListed = profile?.modelNotListed ?: false,
                userProfileIdentificationConfidence = profile?.identificationConfidence?.name ?: "UNKNOWN",
                deviceName = result.deviceName,
                deviceAddressMasked = maskAddress(result.address),
                detectedBrand = result.detectedBrand.displayName,
                detectedBrandFamily = result.detectedBrandFamily.displayName,
                detectedModelFamily = result.detectedModelFamily,
                confirmedModel = result.confirmedModel,
                probableModel = result.probableModel,
                confidence = result.confidence.name,
                matchedProfileId = result.matchedProfileId,
                advertisementServiceUuids = adv?.serviceUuids.orEmpty(),
                advertisementManufacturerIds = adv?.manufacturerIds.orEmpty().map { "0x${it.toString(16)}" },
                advertisementServiceDataUuids = adv?.serviceDataUuids.orEmpty(),
                advertisementRssi = adv?.rssi,
                gattDiscoveryStatus = gatt?.discoveryStatus?.name ?: GattDiscoveryStatus.NOT_ATTEMPTED.name,
                gattServiceUuids = gatt?.serviceUuids.orEmpty(),
                gattCharacteristicCount = gatt?.characteristicCount ?: 0,
                gattCharacteristics = gatt?.services.orEmpty().flatMap { s ->
                    s.characteristics.map { "${it.uuid}|${it.propertyLabel()}" }
                },
                reasons = result.reasons.map { it.machineString() },
                matchedFeatures = result.matchedFeatures.map { it.label() },
                missingFeatures = result.missingFeatures,
                contradictoryFeatures = result.contradictoryFeatures,
                unknownFeatures = result.unknownFeatures,
                errors = gatt?.errors.orEmpty()
            )
        }

        /**
         * Maskiert eine MAC-Adresse datenschutzfreundlich: erste und letzte
         * zwei Oktette bleiben, der Rest wird durch `**` ersetzt.
         * Nicht-MAC-Werte werden nicht geraten.
         */
        fun maskAddress(address: String?): String {
            val parts = address?.trim()?.split(":") ?: return "—"
            if (parts.size != 6) return "—"
            return "${parts[0]}:${parts[1]}:**:**:${parts[4]}:${parts[5]}"
        }
    }
}
