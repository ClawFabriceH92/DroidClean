package com.fabrice.droidclean.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryStatsTest {

    private val hour = 3_600_000L
    private val minute = 60_000L
    private val start = 1_700_000_000_000L

    /** Relevés régulièrement espacés, du pourcentage [from] au pourcentage [to]. */
    private fun ramp(
        at: Long,
        from: Int,
        to: Int,
        durationMs: Long,
        charging: Boolean,
        steps: Int = 12,
    ): List<BatterySample> = (0..steps).map { i ->
        BatterySample(
            at = at + durationMs * i / steps,
            percent = from + (to - from) * i / steps,
            charging = charging,
        )
    }

    // ------------------------------------------------------------- découpage

    @Test
    fun `sessions de décharge - une décharge simple est détectée`() {
        val samples = ramp(start, 100, 20, 16 * hour, charging = false)

        val sessions = BatteryStats.dischargeSessions(samples)

        assertEquals(1, sessions.size)
        assertEquals(80, sessions.single().drainedPercent)
        assertEquals(16 * hour, sessions.single().durationMs)
    }

    @Test
    fun `sessions de décharge - le branchement ferme la session`() {
        val samples = ramp(start, 100, 40, 12 * hour, charging = false) +
            ramp(start + 12 * hour, 40, 100, 2 * hour, charging = true)

        val sessions = BatteryStats.dischargeSessions(samples)

        assertEquals(1, sessions.size)
        assertEquals(100, sessions.single().startPercent)
        assertEquals(40, sessions.single().endPercent)
    }

    @Test
    fun `sessions de décharge - une micro-décharge est écartée`() {
        // 3 % en 30 minutes : trop peu pour extrapoler quoi que ce soit.
        val samples = ramp(start, 80, 77, 30 * minute, charging = false)

        assertTrue(BatteryStats.dischargeSessions(samples).isEmpty())
    }

    @Test
    fun `sessions de décharge - une session trop courte est écartée`() {
        val samples = ramp(start, 80, 60, 10 * minute, charging = false)

        assertTrue(BatteryStats.dischargeSessions(samples).isEmpty())
    }

    @Test
    fun `continuité - un trou de relevés appareil allumé reste une mesure valide`() {
        // Doze nocturne : plus aucun relevé pendant 8 h, mais le téléphone tourne
        // et se décharge. Couper ici jetterait une mesure parfaitement bonne.
        val soir = ramp(start, 100, 80, 3 * hour, charging = false)
        val matin = ramp(start + 11 * hour, 60, 50, hour, charging = false)

        val sessions = BatteryStats.dischargeSessions(soir + matin)

        assertEquals(1, sessions.size)
        assertEquals(50, sessions.single().drainedPercent)
    }

    @Test
    fun `continuité - appareil éteint entre deux relevés, la session est coupée`() {
        // 8 h d'écart sur l'horloge murale, mais l'horloge monotone n'a pas bougé :
        // le téléphone était éteint. Compter ces 8 h comme de l'autonomie la gonflerait.
        val avant = ramp(start, 100, 70, 5 * hour, charging = false)
        val apres = ramp(start + 13 * hour, 70, 30, 5 * hour, charging = false)
            .map { it.copy(realtimeMs = it.at - 8 * hour) }

        val sessions = BatteryStats.dischargeSessions(avant + apres)

        assertEquals(2, sessions.size)
        assertTrue(sessions.all { it.durationMs == 5 * hour })
    }

    @Test
    fun `continuité - un redémarrage coupe la session`() {
        val avant = ramp(start, 100, 70, 5 * hour, charging = false)
        // Après redémarrage, l'horloge monotone repart de zéro puis avance normalement.
        val apres = ramp(start + 6 * hour, 70, 30, 5 * hour, charging = false)
            .map { it.copy(realtimeMs = it.at - (start + 6 * hour)) }

        assertEquals(2, BatteryStats.dischargeSessions(avant + apres).size)
    }

    @Test
    fun `continuité - une charge non observée coupe la session`() {
        // Relevé à 30 %, puis à 95 % toujours « débranché » : une charge a eu lieu
        // sans être vue. Enchaîner les deux ferait passer la décharge pour immense.
        val soir = ramp(start, 100, 30, 10 * hour, charging = false)
        val matin = ramp(start + 14 * hour, 95, 60, 5 * hour, charging = false)

        val sessions = BatteryStats.dischargeSessions(soir + matin)

        assertEquals(2, sessions.size)
        assertTrue(sessions.none { it.drainedPercent > 80 })
    }

    @Test
    fun `continuité - au-delà de douze heures sans relevé, on ne conclut plus`() {
        val avant = ramp(start, 100, 90, 2 * hour, charging = false)
        val apres = ramp(start + 20 * hour, 60, 30, 3 * hour, charging = false)

        assertEquals(2, BatteryStats.dischargeSessions(avant + apres).size)
    }

    @Test
    fun `sessions de charge - détectées symétriquement`() {
        val samples = ramp(start, 20, 100, 2 * hour, charging = true)

        val sessions = BatteryStats.chargeSessions(samples)

        assertEquals(1, sessions.size)
        assertEquals(80, sessions.single().gainedPercent)
    }

    @Test
    fun `relevés désordonnés - remis dans l'ordre avant analyse`() {
        val samples = ramp(start, 100, 20, 16 * hour, charging = false).reversed()

        assertEquals(1, BatteryStats.dischargeSessions(samples).size)
    }

    // -------------------------------------------------------------- moyennes

    @Test
    fun `autonomie moyenne - extrapolée à une charge complète`() {
        // 50 % en 10 h, deux fois : 100 % tiendrait 20 h.
        val sessions = listOf(
            BatteryStats.DischargeSession(start, start + 10 * hour, 100, 50),
            BatteryStats.DischargeSession(start + 20 * hour, start + 30 * hour, 90, 40),
        )

        assertEquals(20 * hour, BatteryStats.averageFullLifeMs(sessions))
    }

    @Test
    fun `autonomie moyenne - pondérée par le pourcentage consommé`() {
        // Une longue décharge de 80 % doit peser plus qu'une courte de 10 %.
        val longue = BatteryStats.DischargeSession(start, start + 16 * hour, 100, 20)
        val courte = BatteryStats.DischargeSession(start + 20 * hour, start + 21 * hour, 50, 40)

        val moyenne = BatteryStats.averageFullLifeMs(listOf(longue, courte))!!

        // Moyenne naïve : (20 h + 10 h) / 2 = 15 h. Pondérée : 17 h / 90 %.
        assertEquals(17 * hour * 100 / 90, moyenne)
        assertTrue("la pondération doit rapprocher de la longue décharge", moyenne > 18 * hour)
    }

    @Test
    fun `autonomie moyenne - une seule session ne suffit pas`() {
        val sessions = listOf(BatteryStats.DischargeSession(start, start + 10 * hour, 100, 50))

        assertNull(BatteryStats.averageFullLifeMs(sessions))
    }

    @Test
    fun `intervalle de recharge - moyenne des écarts entre branchements`() {
        val sessions = listOf(
            BatteryStats.DischargeSession(start, start + 10 * hour, 100, 30),
            BatteryStats.DischargeSession(start + 12 * hour, start + 34 * hour, 100, 25),
            BatteryStats.DischargeSession(start + 36 * hour, start + 58 * hour, 100, 20),
        )

        // Branchements à 10 h, 34 h, 58 h : deux écarts de 24 h.
        assertEquals(24 * hour, BatteryStats.averageRechargeIntervalMs(sessions))
    }

    @Test
    fun `intervalle de recharge - deux sessions ne font pas une moyenne`() {
        val sessions = listOf(
            BatteryStats.DischargeSession(start, start + 10 * hour, 100, 30),
            BatteryStats.DischargeSession(start + 12 * hour, start + 34 * hour, 100, 25),
        )

        assertNull(BatteryStats.averageRechargeIntervalMs(sessions))
    }

    // ------------------------------------------------------------ prévisions

    @Test
    fun `rythme récent - pente de la décharge en cours`() {
        // 10 % en 5 h = 2 %/h.
        val samples = ramp(start, 90, 80, 5 * hour, charging = false)
        val now = start + 5 * hour

        assertEquals(2.0, BatteryStats.recentDrainPerHour(samples, now)!!, 0.01)
    }

    @Test
    fun `rythme récent - ignore ce qui précède le dernier branchement`() {
        // Décharge lente hier, décharge rapide depuis la recharge.
        val hier = ramp(start, 100, 50, 24 * hour, charging = false)
        val charge = ramp(start + 24 * hour, 50, 100, 2 * hour, charging = true)
        val maintenant = ramp(start + 26 * hour, 100, 80, 4 * hour, charging = false)
        val now = start + 30 * hour

        val rate = BatteryStats.recentDrainPerHour(hier + charge + maintenant, now)!!

        assertEquals(5.0, rate, 0.01) // 20 % en 4 h, pas le rythme d'hier
    }

    @Test
    fun `rythme récent - pas de pente sur une durée trop courte`() {
        val samples = ramp(start, 90, 88, 10 * minute, charging = false)

        assertNull(BatteryStats.recentDrainPerHour(samples, start + 10 * minute))
    }

    @Test
    fun `rythme récent - un niveau qui remonte ne donne pas de pente`() {
        val samples = ramp(start, 80, 90, 2 * hour, charging = false)

        assertNull(BatteryStats.recentDrainPerHour(samples, start + 2 * hour))
    }

    @Test
    fun `prévision - temps restant au rythme courant`() {
        val samples = ramp(start, 100, 80, 4 * hour, charging = false) // 5 %/h
        val now = start + 4 * hour

        val forecast = BatteryStats.forecast(samples, now, currentPercent = 80, charging = false)

        // 80 % à 5 %/h = 16 h.
        assertEquals(16 * hour, forecast.timeToEmptyMs)
    }

    @Test
    fun `prévision - sans pente récente, repli sur l'autonomie moyenne`() {
        // Deux décharges anciennes, puis un unique relevé récent : pas de pente.
        val jour1 = ramp(start, 100, 20, 20 * hour, charging = false)
        val charge1 = ramp(start + 20 * hour, 20, 100, 2 * hour, charging = true)
        val jour2 = ramp(start + 22 * hour, 100, 20, 20 * hour, charging = false)
        val charge2 = ramp(start + 42 * hour, 20, 100, 2 * hour, charging = true)
        val now = start + 44 * hour + minute
        val recent = listOf(BatterySample(now, 100, charging = false))

        val forecast = BatteryStats.forecast(
            samples = jour1 + charge1 + jour2 + charge2 + recent,
            now = now,
            currentPercent = 100,
            charging = false,
        )

        assertEquals(25 * hour, forecast.averageFullLifeMs) // 80 % en 20 h -> 100 % en 25 h
        assertEquals(25 * hour, forecast.timeToEmptyMs)
    }

    @Test
    fun `prévision - l'estimation du système prime pour la charge`() {
        val samples = ramp(start, 20, 60, 2 * hour, charging = true)

        val forecast = BatteryStats.forecast(
            samples = samples,
            now = start + 2 * hour,
            currentPercent = 60,
            charging = true,
            platformTimeToFullMs = 45 * minute,
        )

        assertEquals(45 * minute, forecast.timeToFullMs)
        assertNull("pas de temps avant décharge quand on charge", forecast.timeToEmptyMs)
    }

    @Test
    fun `prévision - repli sur le rythme de charge observé quand le système se tait`() {
        // 80 % en 2 h observés -> 100 % en 2 h 30 ; il reste 20 % à faire.
        val samples = ramp(start, 20, 100, 2 * hour, charging = true) +
            ramp(start + 3 * hour, 80, 90, 30 * minute, charging = true)

        val forecast = BatteryStats.forecast(
            samples = samples,
            now = start + 3 * hour + 30 * minute,
            currentPercent = 80,
            charging = true,
            platformTimeToFullMs = -1L,
        )

        assertNotNull(forecast.timeToFullMs)
        assertTrue(forecast.timeToFullMs!! in (20 * minute)..(60 * minute))
    }

    @Test
    fun `prévision - historique vide, rien d'inventé`() {
        val forecast = BatteryStats.forecast(emptyList(), start, 75, charging = false)

        assertNull(forecast.averageFullLifeMs)
        assertNull(forecast.rechargeIntervalMs)
        assertNull(forecast.timeToEmptyMs)
        assertNull(forecast.timeToFullMs)
        assertEquals(0, forecast.sessionCount)
        assertTrue(!forecast.hasAnything)
    }

    @Test
    fun `prévision - batterie pleine et branchée, pas de temps de charge`() {
        val samples = ramp(start, 20, 100, 2 * hour, charging = true)

        val forecast = BatteryStats.forecast(
            samples = samples,
            now = start + 2 * hour,
            currentPercent = 100,
            charging = true,
            platformTimeToFullMs = 10 * minute,
        )

        assertNull(forecast.timeToFullMs)
    }
}
