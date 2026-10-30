package com.syrak.scooterlab

/* ============================================================================
 *  ADAPTER #2:  SEGWAY-NINEBOT / XIAOMI  (5A A5)
 *  ----------------------------------------------------------------------------
 *  Fallback-Adapter, damit die zuvor unterstuetzten Marken (Ninebot Max G2/G30,
 *  ES-Serie, F2 Pro, Xiaomi M365/1S/Pro 2, SoFlow) NICHT verloren gehen.
 *
 *  PROTOKOLL:
 *    Header   : 5A A5
 *    Aufbau   : 5A A5 | LEN | ADDR | CMD | PAYLOAD... | SUM
 *    Pruefsum : SUM = Zweierkomplement der Summe ab Index 2
 *
 *  KEIN Unlock-Handshake (requiresUnlock = false).
 * ==========================================================================*/
class NinebotAdapter : ScooterProtocolAdapter {

    override val id: String = "ninebot-generic"
    override val displayName: String = "Segway-Ninebot / Xiaomi"
    override val protocol: BleProtocol = BleProtocol.NINEBOT

    /**
     * PHASE 10: Ausdruecklich gefuehrte Modelle.
     *
     * SoFlow ist BEWUSST NICHT enthalten: fuer SoFlow existiert kein belegtes
     * Protokoll. Der Transport-Fallback ueber [BleProtocol.NINEBOT] bleibt
     * erhalten, aber es wird keine Tuning-Freigabe abgeleitet.
     */
    override val capabilities = AdapterCapabilities(
        adapterId = "ninebot-generic",
        displayName = "Segway-Ninebot / Xiaomi",
        protocol = BleProtocol.NINEBOT,
        supportedModels = setOf(
            ScooterModel.NINEBOT_MAX_G2,
            ScooterModel.NINEBOT_MAX_G30,
            ScooterModel.NINEBOT_F2_PRO,
            ScooterModel.NINEBOT_ES,
            ScooterModel.XIAOMI_PRO_2,
            ScooterModel.XIAOMI_M365
        ),
        gattServiceUuids = listOf("0000e003-0000-1000-8000-00805f9b34fb"),
        gattWriteCharUuids = listOf(
            "0000fff1-0000-1000-8000-00805f9b34fb",
            "0000fff2-0000-1000-8000-00805f9b34fb"
        ),
        gattNotifyCharUuids = listOf(
            "0000fff1-0000-1000-8000-00805f9b34fb",
            "0000fff2-0000-1000-8000-00805f9b34fb"
        ),
        readOperations = setOf(
            ReadOperation.STATUS_TELEMETRY,
            ReadOperation.BATTERY_BMS
        ),
        writeOperations = setOf(
            WriteOperation.SPEED_CEILING,
            WriteOperation.ZERO_START,
            WriteOperation.KERS,
            WriteOperation.CRUISE,
            WriteOperation.LOCK,
            WriteOperation.REGION,
            WriteOperation.POLICE_MODE,
            WriteOperation.TEMP_GUARD,
            WriteOperation.EMERGENCY_CUT,
            WriteOperation.MAX_POWER,
            WriteOperation.GERMAN_MANEUVER,
            WriteOperation.OVERSPEED_MUTE,
            WriteOperation.REBOOT
        ),
        telemetryDecoding = true,
        diagnostics = true,
        readOnlyByDefault = false
    )

    override val requiresUnlock: Boolean = false

    @Volatile private var unlocked = true
    override val isUnlocked: Boolean get() = unlocked
    override fun setUnlocked(unlocked: Boolean) { this.unlocked = unlocked }

    /* ------------------------------------------------------------------ */
    /*  HANDSHAKE / AUTH                                                   */
    /* ------------------------------------------------------------------ */
    /** Ninebot/Xiaomi benoetigen keinen separaten Unlock-Handshake. */
    override fun handshake(): ByteArray? = null

    /* ------------------------------------------------------------------ */
    /*  KERN-BEFEHLE                                                       */
    /* ------------------------------------------------------------------ */
    override fun buildReadStatusFrame(): ByteArray = ScooterFrames.readTelemetry()

    override fun buildSpeedCeilingFrame(kmh: Int): ByteArray =
        ScooterFrames.maxSpeed(kmh.coerceIn(1, 60))

    override fun buildZeroStartFrame(kmh: Int): ByteArray =
        ScooterFrames.zeroStart(kmh.coerceIn(0, 10))

    override fun buildPoliceModeFrame(legalKmh: Int): ByteArray =
        ScooterFrames.policeMode(legalKmh.coerceIn(1, 45))

    /* ------------------------------------------------------------------ */
    /*  WEITERE TUNING-BEFEHLE                                             */
    /* ------------------------------------------------------------------ */
    override fun buildLockFrame(locked: Boolean): ByteArray = ScooterFrames.lock(locked)
    override fun buildKersFrame(level: Int): ByteArray = ScooterFrames.kers(level.coerceIn(0, 3))
    override fun buildCruiseFrame(enabled: Boolean): ByteArray = ScooterFrames.cruise(enabled)
    override fun buildTempGuardFrame(celsius: Int): ByteArray =
        ScooterFrames.motorTempGuard(celsius.coerceIn(0, 120))
    override fun buildEmergencyCutFrame(): ByteArray = ScooterFrames.emergencyCut()
    override fun buildOverspeedMuteFrame(mute: Boolean): ByteArray = ScooterFrames.overspeedAlarmMute(mute)
    override fun buildMaxPowerFrame(): ByteArray = ScooterFrames.maxPower()
    override fun buildGermanManeuverFrame(): ByteArray = ScooterFrames.germanManeuver()

    /** Ninebot kennt keinen eigenen Region-Befehl -> legales Speed-Limit setzen. */
    override fun buildRegionFrame(region: Region): ByteArray =
        ScooterFrames.maxSpeed(region.legalSpeedKmh)

    /* ------------------------------------------------------------------ */
    /*  FLASH                                                              */
    /* ------------------------------------------------------------------ */
    override fun buildUnlockFrame(): ByteArray? = null

    override fun buildRebootFrame(): ByteArray =
        ScooterFrames.buildFrame(0x20, 0x7E, byteArrayOf(0x01))

    /* ------------------------------------------------------------------ */
    /*  PARSING                                                            */
    /* ------------------------------------------------------------------ */
    override fun parseTelemetry(bytes: ByteArray): Telemetry? =
        TelemetryDecoder.decodeNinebot(bytes)

    /* ------------------------------------------------------------------ */
    /*  FRAME-METADATEN                                                    */
    /* ------------------------------------------------------------------ */
    override fun commandIdOf(frame: ByteArray): Int? = FrameCodec.commandIdOf(frame)
    override fun isValidFrame(frame: ByteArray): Boolean = FrameCodec.isValidFrame(frame)

    /** Ninebot: keine Header-Variante -> nur die Standard-Kodierung. */
    override fun buildOutboundFrames(command: ByteArray): List<ByteArray> = listOf(command)
}
