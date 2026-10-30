package com.syrak.scooterlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 14 — Tests fuer die neue, reine JVM-Logik:
 * Sprachauflösung, Haftungs-/Ländertexte, Kompatibilitätsleiter, GATT-Analyse,
 * Verbindungsqualität und Frische-Bewertung.
 *
 * Diese Tests laufen vollstaendig ohne Android-Geraet.
 */
class Phase14SupportTest {

    /* ---------------- LanguageResolver ---------------- */

    @Test
    fun `german system language resolves to german`() {
        assertEquals(AppLanguage.DE, LanguageResolver.resolve("de-DE", "DE"))
        assertEquals(AppLanguage.DE, LanguageResolver.resolve("de", "US"))
    }

    @Test
    fun `english system language resolves to english`() {
        assertEquals(AppLanguage.EN, LanguageResolver.resolve("en-US", "US"))
        assertEquals(AppLanguage.EN, LanguageResolver.resolve("en-GB", "DE"))
    }

    @Test
    fun `unsupported system language falls back to country language`() {
        // Franzoesisch ist nicht unterstuetzt -> Land DE -> Deutsch.
        assertEquals(AppLanguage.DE, LanguageResolver.resolve("fr-FR", "DE"))
        // Land AT ist deutschsprachig.
        assertEquals(AppLanguage.DE, LanguageResolver.resolve("it-IT", "AT"))
    }

    @Test
    fun `unsupported language and non german country falls back to english`() {
        assertEquals(AppLanguage.EN, LanguageResolver.resolve("ja-JP", "US"))
        assertEquals(AppLanguage.EN, LanguageResolver.resolve(null, null))
        assertEquals(AppLanguage.EN, LanguageResolver.resolve("", ""))
    }

    @Test
    fun `fallbackForCountry knows german speaking countries`() {
        assertEquals(AppLanguage.DE, LanguageResolver.fallbackForCountry("DE"))
        assertEquals(AppLanguage.DE, LanguageResolver.fallbackForCountry("ch"))
        assertEquals(AppLanguage.EN, LanguageResolver.fallbackForCountry("US"))
        assertEquals(AppLanguage.EN, LanguageResolver.fallbackForCountry(null))
    }

    /* ---------------- DisclaimerText ---------------- */

    @Test
    fun `english disclaimer keeps all seven sections`() {
        assertEquals(7, DisclaimerText.sections(AppLanguage.EN).size)
        assertEquals(7, DisclaimerText.sections(AppLanguage.DE).size)
    }

    @Test
    fun `disclaimer sections are non empty and have distinct headings`() {
        listOf(AppLanguage.EN, AppLanguage.DE).forEach { lang ->
            val sections = DisclaimerText.sections(lang)
            assertTrue(sections.all { it.heading.isNotBlank() && it.body.length > 40 })
            assertEquals(sections.size, sections.map { it.heading }.distinct().size)
        }
    }

    @Test
    fun `english is the default presentation language for the general notice`() {
        // Die App startet mit Englisch (siehe MainActivity.disclaimerLanguage).
        assertEquals("English", AppLanguage.EN.displayName)
        assertTrue(DisclaimerText.title(AppLanguage.EN).isNotBlank())
        assertTrue(DisclaimerText.emergency(AppLanguage.EN).isNotBlank())
    }

    @Test
    fun `disclaimer helper texts differ by language`() {
        assertTrue(DisclaimerText.acceptLabel(AppLanguage.EN) != DisclaimerText.acceptLabel(AppLanguage.DE))
        assertTrue(DisclaimerText.gateHint(AppLanguage.EN, 10).contains("10"))
        assertTrue(DisclaimerText.gateHint(AppLanguage.DE, 10).contains("10"))
    }

    /* ---------------- CountryNoticeTexts ---------------- */

    @Test
    fun `german country notice keeps catalog content unchanged`() {
        val de = CountryNoticeTexts.forRegion(Region.DE, AppLanguage.DE)
        val base = CountryLegalCatalog.forRegion(Region.DE)
        assertEquals(base.displayName, de.displayName)
        assertEquals(base.legalSpeedKmh, de.legalSpeedKmh)
        assertEquals(base.verified, de.verified)
        assertEquals(base.source, de.source)
        assertEquals(base.sourceDate, de.sourceDate)
        assertEquals(base.points, de.points)
    }

    @Test
    fun `english country notice keeps same legal facts as german`() {
        val de = CountryNoticeTexts.forRegion(Region.DE, AppLanguage.DE)
        val en = CountryNoticeTexts.forRegion(Region.DE, AppLanguage.EN)
        assertEquals(de.code, en.code)
        assertEquals(de.legalSpeedKmh, en.legalSpeedKmh)
        assertEquals(de.verified, en.verified)
        assertEquals(de.sourceDate, en.sourceDate)
        assertEquals(AppLanguage.EN, en.language)
    }

    @Test
    fun `country notice points are localized but never empty`() {
        Region.values().forEach { region ->
            listOf(AppLanguage.DE, AppLanguage.EN).forEach { lang ->
                val notice = CountryNoticeTexts.forRegion(region, lang)
                assertTrue("region=$region lang=$lang", notice.points.isNotEmpty())
                assertTrue(notice.points.all { it.isNotBlank() })
                assertNotNull(notice.source)
                assertNotNull(notice.sourceDate)
            }
        }
    }

    @Test
    fun `country notice exposes catalog date for transparency`() {
        assertTrue(CountryLegalCatalog.CATALOG_DATE.isNotBlank())
        assertTrue(
            CountryNoticeTexts.transparency(AppLanguage.EN, CountryLegalCatalog.CATALOG_DATE)
                .contains(CountryLegalCatalog.CATALOG_DATE)
        )
    }

    /* ---------------- CompatibilityLadder ---------------- */

    @Test
    fun `ladder shows no stage when nothing is proven`() {
        val report = CompatibilityLadder.evaluate(CompatibilityEvidence())
        assertNull(report.highest)
        assertEquals(6, report.missing.size)
        assertTrue(report.stages.none { it.reached })
    }

    @Test
    fun `ladder is cumulative and stops at first gap`() {
        val report = CompatibilityLadder.evaluate(
            CompatibilityEvidence(
                detected = true,
                gattDiscovered = true,
                readOnlyTested = false,
                telemetryValidated = true,
                writeProtocolValidated = true,
                hardwareVerified = true
            )
        )
        assertEquals(CompatibilityStage.GATT_DISCOVERED, report.highest)
        assertFalse(report.stages.first { it.stage == CompatibilityStage.TELEMETRY_VALIDATED }.reached)
        assertEquals(4, report.missing.size)
    }

    @Test
    fun `contradictory hardware verification without detection is downgraded`() {
        val report = CompatibilityLadder.evaluate(
            CompatibilityEvidence(hardwareVerified = true, writeProtocolValidated = true)
        )
        assertNull(report.highest)
    }

    @Test
    fun `full evidence reaches the highest stage`() {
        val report = CompatibilityLadder.evaluate(
            CompatibilityEvidence(
                detected = true,
                gattDiscovered = true,
                readOnlyTested = true,
                telemetryValidated = true,
                writeProtocolValidated = true,
                hardwareVerified = true
            )
        )
        assertEquals(CompatibilityStage.HARDWARE_VERIFIED, report.highest)
        assertTrue(report.missing.isEmpty())
        assertEquals(6, CompatibilityLadder.reachedStages(CompatibilityEvidence(
            detected = true, gattDiscovered = true, readOnlyTested = true,
            telemetryValidated = true, writeProtocolValidated = true, hardwareVerified = true
        )).size)
    }

    /* ---------------- GattAnalyzer ---------------- */

    @Test
    fun `empty gatt tree is reported as empty and not invented`() {
        val analysis = GattAnalyzer.analyze(emptyList())
        assertEquals(GattCompleteness.EMPTY, analysis.completeness)
        assertEquals(0, analysis.services)
        assertEquals(0, analysis.characteristics)
        assertTrue(analysis.notes.isNotEmpty())
    }

    @Test
    fun `gatt analysis counts properties correctly`() {
        val services = listOf(
            ServiceFacts(
                uuid = "180f",
                characteristics = listOf(
                    CharacteristicFacts("2a19", readable = true, notifying = true),
                    CharacteristicFacts("2a1a", readable = true, writable = true)
                )
            ),
            ServiceFacts(
                uuid = "180a",
                characteristics = listOf(CharacteristicFacts("2a29", readable = true, indicating = true))
            )
        )
        val analysis = GattAnalyzer.analyze(services)
        assertEquals(2, analysis.services)
        assertEquals(3, analysis.characteristics)
        assertEquals(3, analysis.readable)
        assertEquals(1, analysis.writable)
        assertEquals(1, analysis.notifying)
        assertEquals(1, analysis.indicating)
        assertEquals(GattCompleteness.COMPLETE, analysis.completeness)
    }

    @Test
    fun `gatt analysis flags partial tree and missing notify`() {
        val services = listOf(
            ServiceFacts(uuid = "180f", characteristics = emptyList())
        )
        val analysis = GattAnalyzer.analyze(services)
        assertEquals(GattCompleteness.PARTIAL, analysis.completeness)
        assertTrue(analysis.notes.any { it.contains("Notify") || it.contains("Keine") })
    }

    /* ---------------- LinkQuality ---------------- */

    @Test
    fun `empty link samples are unavailable`() {
        val summary = LinkQuality.summarize(emptyList(), 1_000L)
        assertEquals(0, summary.samples)
        assertNull(summary.avgDbm)
        assertEquals(LinkTrend.UNKNOWN, summary.trend)
    }

    @Test
    fun `link summary computes average and marks stale samples`() {
        val samples = listOf(
            LinkSample(-60, 0L),
            LinkSample(-70, 1_000L),
            LinkSample(-80, 2_000L)
        )
        val fresh = LinkQuality.summarize(samples, 3_000L, staleAfterMs = 15_000L)
        assertEquals(-70, fresh.avgDbm)
        assertFalse(fresh.stale)

        val stale = LinkQuality.summarize(samples, 60_000L, staleAfterMs = 15_000L)
        assertTrue(stale.stale)
    }

    @Test
    fun `trend needs at least four samples`() {
        assertEquals(LinkTrend.UNKNOWN, LinkQuality.trendOf(listOf(LinkSample(-60, 0L))))
        assertEquals(LinkTrend.UNKNOWN, LinkQuality.trendOf(listOf(
            LinkSample(-60, 0L), LinkSample(-60, 1L), LinkSample(-60, 2L)
        )))
    }

    @Test
    fun `trend detects improving and degrading`() {
        val improving = listOf(
            LinkSample(-90, 0L), LinkSample(-90, 1L),
            LinkSample(-60, 2L), LinkSample(-60, 3L)
        )
        assertEquals(LinkTrend.IMPROVING, LinkQuality.trendOf(improving))

        val degrading = listOf(
            LinkSample(-60, 0L), LinkSample(-60, 1L),
            LinkSample(-90, 2L), LinkSample(-90, 3L)
        )
        assertEquals(LinkTrend.DEGRADING, LinkQuality.trendOf(degrading))
    }

    @Test
    fun `small noise is not reported as a trend`() {
        val noisy = listOf(
            LinkSample(-70, 0L), LinkSample(-71, 1L),
            LinkSample(-70, 2L), LinkSample(-71, 3L)
        )
        assertEquals(LinkTrend.STABLE, LinkQuality.trendOf(noisy))
    }

    /* ---------------- Freshness ---------------- */

    @Test
    fun `freshness reports never when no timestamp exists`() {
        assertEquals(FreshnessState.NEVER, Freshness.state(null, 1_000L))
        assertEquals(FreshnessState.NEVER, Freshness.state(0L, 1_000L))
        assertNull(Freshness.ageSeconds(null, 1_000L))
    }

    @Test
    fun `freshness transitions fresh aging stale`() {
        val now = 10_000L
        assertEquals(FreshnessState.FRESH, Freshness.state(9_000L, now, 3_000L))
        assertEquals(FreshnessState.AGING, Freshness.state(6_000L, now, 3_000L))
        assertEquals(FreshnessState.STALE, Freshness.state(1_000L, now, 3_000L))
    }

    @Test
    fun `freshness age is reported in seconds`() {
        assertEquals(5, Freshness.ageSeconds(5_000L, 10_000L))
        assertEquals(0, Freshness.ageSeconds(11_000L, 10_000L))
    }

    @Test
    fun `aging values stay usable but stale values do not`() {
        assertTrue(FreshnessState.FRESH.isUsable)
        assertTrue(FreshnessState.AGING.isUsable)
        assertFalse(FreshnessState.STALE.isUsable)
        assertFalse(FreshnessState.NEVER.isUsable)
    }
}
