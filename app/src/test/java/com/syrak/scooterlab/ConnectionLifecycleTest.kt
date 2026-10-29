package com.syrak.scooterlab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressionstests fuer den in Phase 5 belegten Scan-Timeout-Defekt:
 * Ein Timeout eines frueheren Scans durfte einen neuen Scan nicht beenden.
 */
class ScanTimeoutGuardTest {

    @Test
    fun freshGuard_hasNoCurrentToken() {
        val guard = ScanTimeoutGuard()
        assertFalse(guard.isCurrent(0))
        assertFalse(guard.isCurrent(1))
    }

    @Test
    fun tokenOfRunningScan_isCurrent() {
        val guard = ScanTimeoutGuard()
        val token = guard.begin()
        assertTrue(guard.isCurrent(token))
    }

    @Test
    fun staleTimeoutAfterEnd_isIgnored() {
        val guard = ScanTimeoutGuard()
        val token = guard.begin()
        guard.end()
        assertFalse("Timeout nach end() darf nicht mehr wirken", guard.isCurrent(token))
    }

    @Test
    fun staleTimeoutCannotStopNewerScan() {
        val guard = ScanTimeoutGuard()
        val first = guard.begin()
        guard.end()
        val second = guard.begin()
        assertFalse("Alter Timeout darf neuen Scan nicht beenden", guard.isCurrent(first))
        assertTrue("Neuer Timeout muss wirken", guard.isCurrent(second))
    }

    @Test
    fun eachBeginProducesDistinctToken() {
        val guard = ScanTimeoutGuard()
        val first = guard.begin()
        val second = guard.begin()
        assertFalse(guard.isCurrent(first))
        assertTrue(guard.isCurrent(second))
    }
}

/**
 * Regressionstests fuer den in Phase 5 belegten Doppel-Verbindungs-Defekt:
 * Beim Geraetewechsel muss eine bestehende Engine zuerst getrennt werden.
 */
class ConnectionSwitchPolicyTest {

    @Test
    fun noExistingEngine_noTeardownNeeded() {
        assertFalse(ConnectionSwitchPolicy.mustTearDownExisting(false))
    }

    @Test
    fun existingEngine_requiresTeardown() {
        assertTrue(ConnectionSwitchPolicy.mustTearDownExisting(true))
    }
}
