package com.syrak.scooterlab

/* ---------------------------------------------------------------------------
 *  FIRMWARE-KATALOG
 * ------------------------------------------------------------------------- */
object FirmwareCatalog {
    private val shfwDrv = FirmwarePackage("shfw-drv", "SHFW DRV", "0.3.0", "DRV",
        "Referenz: ScooterHacking-Repository (kein offizieller Endpunkt)", 32, "ScooterHacking Firmware für Ninebot/Xiaomi — volle Parameterkontrolle.")
    private val shfwBms = FirmwarePackage("shfw-bms", "SHFW BMS", "1.5.5", "BMS",
        "Referenz: ScooterHacking-Repository (kein offizieller Endpunkt)", 0, "BMS-Firmware für Ninebot/Xiaomi — Zellbalance & Schutzparameter.")
    private val stockDrv = FirmwarePackage("stock-drv", "Werks-DRV (Stock)", "factory", "DRV",
        "Referenz: Segway-Ninebot Werks-OTA (kein offizieller Endpunkt)", 20, "Original-Werksfirmware — legaler Auslieferungszustand.")
    private val stockBms = FirmwarePackage("stock-bms", "Werks-BMS (Stock)", "factory", "BMS",
        "Referenz: Segway-Ninebot Werks-OTA (kein offizieller Endpunkt)", 0, "Original-BMS-Firmware.")
    private val naveeProfile = FirmwarePackage("navee-ble-profile", "Navee BLE Performance Profile", "1.0.0", "BLE",
        "Referenz: Navee Werks-Firmware (kein offizieller Endpunkt)", 32, "BLE-Profil-Patch für Navee.")
    private val soflowProfile = FirmwarePackage("soflow-ble-profile", "SoFlow BLE Performance Profile", "1.0.0", "BLE",
        "Referenz: SoFlow Werks-Firmware (kein offizieller Endpunkt)", 25, "BLE-Profil-Patch für SoFlow.")
    private val naveeGt3MaxStockEu = FirmwarePackage("navee-gt3max-stock-eu", "Navee GT3 Max Stock (EU)", "v2.0.3.1", "DRV",
        "Referenz: Navee GT3 Max EU-Werks-Firmware (kein offizieller Endpunkt)", 20, "Original EU-Werksfirmware. Stellt das legale 20-km/h-eKFV-Limit wieder her.")
    private val naveeGt3MaxUsPerf = FirmwarePackage("navee-gt3max-us-perf", "Navee GT3 Max US Performance", "v2.0.3.1-US", "DRV",
        "Referenz: Navee GT3 Max US-Performance (kein offizieller Endpunkt)", 40, "US-Performance-Payload für die Brightway-Engine.")
    private val naveeGt3MaxUnlock = FirmwarePackage("navee-gt3max-unlock", "Navee GT3 Max Unlock Profile", "1.0.0", "BLE",
        "Referenz: Navee GT3 Max Unlock-Profil (kein offizieller Endpunkt)", 32, "Unlock-Profil — hebt die interne Drosselung auf die Easy-Speed-Grenze (32 km/h).")
    private val naveeS65StockEu = FirmwarePackage("navee-s65-stock-eu", "Navee S65 / N65 / V40 Stock (EU)", "v1.8.2", "DRV",
        "Referenz: Navee S65/N65/V40 EU-Werks-Firmware (kein offizieller Endpunkt)", 20, "Original EU-Werksfirmware für S65 / N65 / V40.")
    private val naveeS65Unlock = FirmwarePackage("navee-s65-unlock", "Navee S65 Unlock Profile", "1.0.0", "BLE",
        "Referenz: Navee S65 Unlock-Profil (kein offizieller Endpunkt)", 30, "Unlock-Profil für S65 / N65 / V40.")
    private val brightwayUnlock = FirmwarePackage("brightway-unlock", "Brightway Engine Unlock", "1.0.0", "BLE",
        "Referenz: Brightway Engine Unlock-Profil (kein offizieller Endpunkt)", 32, "Generisches Brightway-Engine-Unlock-Profil.")

    // ---- Community-Quellen (Neo-Hacker Pro) --------------------------------
    private val miFwInfoNavee = FirmwarePackage("mi-fw-navee", "mi-fw-info Navee/Brightway Patch", "community", "DRV",
        "Referenz: mi-fw-info (Streamlit) (kein offizieller Endpunkt)", 32, "Community-Patch (mi-fw-info) fuer Navee/Brightway.")
    private val miFwInfoXiaomi = FirmwarePackage("mi-fw-xiaomi", "mi-fw-info Xiaomi Custom", "community", "DRV",
        "Referenz: mi-fw-info (Streamlit) (kein offizieller Endpunkt)", 32, "Community-Patch (mi-fw-info) fuer Xiaomi.")
    private val shfwRepo = FirmwarePackage("shfw-repo", "SHFW Repository Build", "nightly", "DRV",
        "Referenz: ScooterHacking / SHFW (kein offizieller Endpunkt)", 32, "SHFW-Build aus dem ScooterHacking-Repository.")
    private val naveeFlasherGt3 = FirmwarePackage("navee-flasher-gt3", "Navee Flasher GT3 Max/Pro", "verified", "DRV",
        "Referenz: Navee-Flasher-Repository (kein offizieller Endpunkt)", 40, "Verifizierte DRV-Binary (Navee Flasher) fuer GT3 Max/Pro.")
    private val naveeFlasherSt3 = FirmwarePackage("navee-flasher-st3", "Navee Flasher ST3/N65/S65/V40", "verified", "DRV",
        "Referenz: Navee-Flasher-Repository (kein offizieller Endpunkt)", 40, "Verifizierte DRV-Binary (Navee Flasher) fuer ST3/N65/S65/V40.")
    private val m365Downg = FirmwarePackage("m365-downg", "M365 DownG / Ninebot Flasher", "stock", "DRV",
        "Referenz: M365 DownG / Ninebot Flasher (kein offizieller Endpunkt)", 25, "Stock-/Downgrade-Binary via M365 DownG / Ninebot Flasher.")

    /** Liefert alle Pakete, die einer Community-Quelle zugeordnet sind. */
    fun fromSource(source: FirmwareSource, model: ScooterModel): List<FirmwarePackage> {
        val navee = model.protocol == BleProtocol.NAVEE_BRIGHTWAY
        val ninebot = model in listOf(
            ScooterModel.NINEBOT_MAX_G2, ScooterModel.NINEBOT_MAX_G30, ScooterModel.NINEBOT_F2_PRO,
            ScooterModel.NINEBOT_ES, ScooterModel.XIAOMI_PRO_2, ScooterModel.XIAOMI_M365
        )
        return when (source) {
            FirmwareSource.MI_FW_INFO ->
                if (navee) listOf(miFwInfoNavee, naveeProfile) else listOf(miFwInfoXiaomi, naveeProfile)
            FirmwareSource.SCOOTER_HACKING ->
                if (ninebot) listOf(shfwRepo, shfwDrv, shfwBms) else listOf(shfwRepo, naveeProfile)
            FirmwareSource.NAVEE_FLASHER ->
                when (model) {
                    ScooterModel.NAVEE_GT3_MAX, ScooterModel.NAVEE_GT3_PRO -> listOf(naveeFlasherGt3, naveeGt3MaxUnlock)
                    ScooterModel.NAVEE_ST3, ScooterModel.NAVEE_N65, ScooterModel.NAVEE_V40, ScooterModel.NAVEE_S65 ->
                        listOf(naveeFlasherSt3, naveeS65Unlock)
                    else -> listOf(naveeFlasherGt3, naveeProfile)
                }
            FirmwareSource.M365_DOWNG ->
                if (ninebot) listOf(m365Downg, stockDrv) else listOf(m365Downg, naveeProfile)
            FirmwareSource.FACTORY ->
                if (navee) listOf(naveeGt3MaxStockEu, naveeS65StockEu, stockDrv)
                else listOf(stockDrv, stockBms)
        }
    }

    fun forModel(model: ScooterModel): List<FirmwarePackage> = when (model) {
        ScooterModel.NINEBOT_MAX_G2, ScooterModel.NINEBOT_MAX_G30, ScooterModel.NINEBOT_F2_PRO,
        ScooterModel.NINEBOT_ES, ScooterModel.XIAOMI_PRO_2, ScooterModel.XIAOMI_M365 ->
            listOf(shfwDrv, shfwBms, stockDrv, stockBms)
        ScooterModel.NAVEE_GT3_MAX, ScooterModel.NAVEE_GT3_PRO ->
            listOf(naveeGt3MaxUnlock, naveeGt3MaxUsPerf, naveeGt3MaxStockEu, naveeProfile)
        ScooterModel.NAVEE_ST3, ScooterModel.NAVEE_N65, ScooterModel.NAVEE_V40, ScooterModel.NAVEE_S65 ->
            listOf(naveeS65Unlock, naveeS65StockEu, brightwayUnlock, naveeProfile)
        ScooterModel.SOFLOW_SO4 -> listOf(soflowProfile, stockDrv)
        ScooterModel.UNKNOWN -> listOf(stockDrv, stockBms)
    }

    fun recommended(model: ScooterModel): FirmwarePackage? = forModel(model).firstOrNull()
}
