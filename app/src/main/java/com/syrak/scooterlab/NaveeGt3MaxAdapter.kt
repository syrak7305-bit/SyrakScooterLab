package com.syrak.scooterlab

/* ============================================================================
 *  PRIMÄRER ADAPTER #1:  NAVEE GT3 MAX  (Brightway-Engine)
 *  ----------------------------------------------------------------------------
 *  Referenzmodell: NAVEE GT3 Max, DEUTSCHE eKFV-VERSION (20 km/h).
 *
 *  PROTOKOLL (Brightway):
 *    Header   : AA 55  (Standard)  /  55 AA  (Alt-Header, FW-Revisions-Fallback)
 *    Aufbau   : AA 55 | CMD | LEN | PAYLOAD... | SUM
 *    Pruefsum : SUM  = (Summe aller Bytes von Index 0 bis LEN-1) & 0xFF
 *    Alt-Kod. : XOR  = (XOR aller Bytes von Index 0 bis LEN-1) & 0xFF
 *
 *  PFLICHT-HANDSHAKE (Unlock):
 *    CMD 0x01, Payload 0x01  ->  AA 55 01 01 01
 *    Ohne quittierten Handshake wird KEIN Tuning-Befehl akzeptiert.
 *
 *  STATUS-REQUEST:
 *    CMD 0x20, Payload 0x00  ->  AA 55 20 01 00 76
 *
 *  REGION / SPEED:
 *    CMD 0x10 = Geschwindigkeits-Limit (km/h)
 *    CMD 0x12 = Region-/Laendercode
 *
 *  EHRLICHKEIT: Die GATT-Mechanik + Frame-Kodierung sind vollstaendig real.
 *  Die exakten Telemetrie-Byte-Offsets sind herstellerspezifisch und stehen
 *  als benannte Konstanten in [TelemetryDecoder] (nach Mitschnitt 1-zeilig
 *  korrigierbar). Es werden KEINE Werte erfunden — nicht Empfangenes bleibt null.
 * ==========================================================================*/
class NaveeGt3MaxAdapter : ScooterProtocolAdapter {

    override val id: String = "navee-gt3max"
    override val displayName: String = "Navee GT3 Max (Brightway)"
    override val protocol: BleProtocol = BleProtocol.NAVEE_BRIGHTWAY
    override val requiresUnlock: Boolean = true

    @Volatile private var unlocked = false
    override val isUnlocked: Boolean get() = unlocked
    override fun setUnlocked(unlocked: Boolean) { this.unlocked = unlocked }

    /* ------------------------------------------------------------------ */
    /*  HANDSHAKE / AUTH                                                   */
    /* ------------------------------------------------------------------ */
    override fun handshake(): ByteArray = NaveeFrames.handshake()

    /* ------------------------------------------------------------------ */
    /*  KERN-BEFEHLE                                                       */
    /* ------------------------------------------------------------------ */
    override fun buildReadStatusFrame(): ByteArray = NaveeFrames.readStatus()

    override fun buildSpeedCeilingFrame(kmh: Int): ByteArray =
        NaveeFrames.speedCeiling(kmh.coerceIn(1, 60))

    override fun buildZeroStartFrame(kmh: Int): ByteArray =
        NaveeFrames.zeroStart(kmh.coerceIn(0, 10))

    override fun buildPoliceModeFrame(legalKmh: Int): ByteArray =
        NaveeFrames.policeMode(legalKmh.coerceIn(1, 45))

    /* ------------------------------------------------------------------ */
    /*  WEITERE TUNING-BEFEHLE                                             */
    /* ------------------------------------------------------------------ */
    override fun buildLockFrame(locked: Boolean): ByteArray = NaveeFrames.lock(locked)
    override fun buildKersFrame(level: Int): ByteArray = NaveeFrames.kers(level.coerceIn(0, 3))
    override fun buildCruiseFrame(enabled: Boolean): ByteArray = NaveeFrames.cruise(enabled)
    override fun buildTempGuardFrame(celsius: Int): ByteArray =
        NaveeFrames.motorTempGuard(celsius.coerceIn(0, 120))
    override fun buildEmergencyCutFrame(): ByteArray = NaveeFrames.emergencyCut()
    override fun buildOverspeedMuteFrame(mute: Boolean): ByteArray = NaveeFrames.overspeedAlarmMute(mute)
    override fun buildMaxPowerFrame(): ByteArray = NaveeFrames.maxPower()
    override fun buildGermanManeuverFrame(): ByteArray = NaveeFrames.germanManeuver()

    override fun buildRegionFrame(region: Region): ByteArray =
        NaveeFrames.regionToggle(regionCode(region))

    /** Brightway-Region-/Laendercode aus dem [Region]-Enum. */
    private fun regionCode(region: Region): Int = when (region) {
        Region.DE -> 0x01
        Region.AT -> 0x02
        Region.CH -> 0x03
        Region.US -> 0x04
        Region.WORLD -> 0x00
    }

    /* ------------------------------------------------------------------ */
    /*  FLASH                                                              */
    /* ------------------------------------------------------------------ */
    override fun buildUnlockFrame(): ByteArray? = handshake()

    override fun buildRebootFrame(): ByteArray =
        NaveeFrames.buildFrame(NaveeFrames.CMD_EMERGENCY, byteArrayOf(0x01))

    /* ------------------------------------------------------------------ */
    /*  PARSING                                                            */
    /* ------------------------------------------------------------------ */
    override fun parseTelemetry(bytes: ByteArray): Telemetry? =
        TelemetryDecoder.decodeNavee(bytes)

    /* ------------------------------------------------------------------ */
    /*  FRAME-METADATEN                                                    */
    /* ------------------------------------------------------------------ */
    override fun commandIdOf(frame: ByteArray): Int? = FrameCodec.commandIdOf(frame)
    override fun isValidFrame(frame: ByteArray): Boolean = FrameCodec.isValidFrame(frame)

    /**
     * Fallback-Kette fuer Brightway:
     *   [Standard-Header AA 55,  Alt-Header 55 AA]
     * Manche GT3-Max-FW-Revisionen quittieren nur eine der beiden Kodierungen.
     */
    override fun buildOutboundFrames(command: ByteArray): List<ByteArray> {
        val cmd = commandIdOf(command) ?: return listOf(command)
        val payload = if (command.size > 4) command.copyOfRange(4, command.size - 1) else ByteArray(0)
        val alt = NaveeFrames.buildFrameAlt(cmd, payload)
        val list = ArrayList<ByteArray>(2)
        list.add(command)
        if (!alt.contentEquals(command)) list.add(alt)
        return list
    }
}
