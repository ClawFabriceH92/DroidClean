package com.fabrice.droidclean.battery

/** Un relevé du niveau de batterie à un instant donné. */
data class BatterySample(
    /** Horloge murale, en millisecondes. */
    val at: Long,
    val percent: Int,
    /** Appareil branché sur une source d'alimentation. */
    val charging: Boolean,
    /**
     * Horloge monotone (`SystemClock.elapsedRealtime`). Elle avance pendant le
     * Doze mais **pas** appareil éteint, et repart de zéro au redémarrage :
     * comparer sa progression à celle de l'horloge murale est le seul moyen
     * fiable de savoir que le téléphone a été éteint entre deux relevés.
     *
     * Par défaut égale à [at], ce qui revient à supposer l'appareil resté allumé.
     */
    val realtimeMs: Long = at,
)

/**
 * Autonomie observée : combien de temps tient une charge, tous les combien
 * l'appareil est rebranché, et combien de temps il reste avant la prochaine
 * recharge.
 *
 * Android ne fournit aucune de ces informations : la plateforme expose le niveau
 * instantané, et rien d'autre. Tout se déduit donc d'un historique de relevés
 * que l'application constitue elle-même — d'où la période d'apprentissage.
 *
 * Sans dépendance Android, donc testable : c'est un calcul statistique, il n'a
 * pas à être vérifié sur un téléphone.
 */
object BatteryStats {

    /** Une période de décharge : du débranchement au rebranchement. */
    data class DischargeSession(
        val startAt: Long,
        val endAt: Long,
        val startPercent: Int,
        val endPercent: Int,
    ) {
        val durationMs: Long get() = endAt - startAt
        val drainedPercent: Int get() = startPercent - endPercent
    }

    /** Une période de charge : du branchement au débranchement. */
    data class ChargeSession(
        val startAt: Long,
        val endAt: Long,
        val startPercent: Int,
        val endPercent: Int,
    ) {
        val durationMs: Long get() = endAt - startAt
        val gainedPercent: Int get() = endPercent - startPercent
    }

    /** Ce que l'interface affiche. `null` = pas encore assez de données. */
    data class Forecast(
        /** Autonomie moyenne d'une charge complète, 100 % → 0 %. */
        val averageFullLifeMs: Long?,
        /** Intervalle moyen entre deux recharges. */
        val rechargeIntervalMs: Long?,
        /** Temps restant avant que la batterie soit vide, au rythme constaté. */
        val timeToEmptyMs: Long?,
        /** Temps restant avant la charge complète. */
        val timeToFullMs: Long?,
        /** Nombre de cycles de décharge exploitables observés. */
        val sessionCount: Int,
    ) {
        val hasAnything: Boolean
            get() = averageFullLifeMs != null || rechargeIntervalMs != null ||
                timeToEmptyMs != null || timeToFullMs != null
    }

    /** Une décharge de moins de 8 % ne dit rien de fiable sur l'autonomie. */
    const val MIN_DRAIN_PERCENT = 8

    /** Idem pour une session trop courte : le bruit domine. */
    const val MIN_SESSION_MS = 20 * 60_000L

    /**
     * Au-delà, les deux relevés sont trop éloignés pour qu'on sache ce qui s'est
     * passé entre eux. Généreux à dessein : un trou de plusieurs heures dû au
     * Doze reste une mesure de décharge parfaitement valide, du moment que
     * l'appareil est resté allumé et débranché — ce que les deux détecteurs
     * ci-dessous vérifient précisément.
     */
    const val MAX_GAP_MS = 12 * 3_600_000L

    /**
     * Écart toléré entre horloge murale et horloge monotone. Au-delà, l'appareil
     * a été éteint : compter ce temps comme de l'autonomie la gonflerait.
     */
    const val POWERED_OFF_TOLERANCE_MS = 5 * 60_000L

    /** Une remontée de niveau pendant une décharge trahit une charge non observée. */
    const val LEVEL_RISE_TOLERANCE = 1

    /** Fenêtre d'observation du rythme de décharge courant. */
    const val RECENT_WINDOW_MS = 6 * 3_600_000L

    /** En dessous, la pente récente n'a pas de sens. */
    const val MIN_RECENT_SPAN_MS = 25 * 60_000L

    private const val HOUR_MS = 3_600_000.0

    // ------------------------------------------------------------- découpage

    /**
     * Découpe l'historique en périodes de décharge.
     *
     * Une session commence au débranchement et se termine au rebranchement. Les
     * sessions traversant un trou de relevés (appareil éteint) sont coupées :
     * compter le temps hors tension comme de l'autonomie gonflerait le résultat.
     */
    fun dischargeSessions(samples: List<BatterySample>): List<DischargeSession> =
        sessions(samples, discharging = true).mapNotNull { (first, last) ->
            DischargeSession(first.at, last.at, first.percent, last.percent)
                .takeIf { it.drainedPercent >= MIN_DRAIN_PERCENT && it.durationMs >= MIN_SESSION_MS }
        }

    fun chargeSessions(samples: List<BatterySample>): List<ChargeSession> =
        sessions(samples, discharging = false).mapNotNull { (first, last) ->
            ChargeSession(first.at, last.at, first.percent, last.percent)
                .takeIf { it.gainedPercent >= MIN_DRAIN_PERCENT && it.durationMs >= MIN_SESSION_MS }
        }

    /**
     * Runs consécutifs de même état d'alimentation, coupés dès que la continuité
     * de la mesure n'est plus garantie.
     */
    private fun sessions(
        samples: List<BatterySample>,
        discharging: Boolean,
    ): List<Pair<BatterySample, BatterySample>> {
        val ordered = samples.sortedBy { it.at }
        val runs = ArrayList<Pair<BatterySample, BatterySample>>()
        var first: BatterySample? = null
        var previous: BatterySample? = null

        fun close() {
            val start = first
            val end = previous
            if (start != null && end != null && start !== end) runs.add(start to end)
            first = null
            previous = null
        }

        for (sample in ordered) {
            if (sample.charging == discharging) {
                // État d'alimentation opposé à celui qu'on suit : la session s'arrête.
                close()
                continue
            }
            val last = previous
            if (last != null && breaksContinuity(last, sample, discharging)) close()
            if (first == null) first = sample
            previous = sample
        }
        close()
        return runs
    }

    /**
     * La mesure entre ces deux relevés est-elle inexploitable ?
     *
     * Trois cas, tous invisibles sur l'horloge murale seule :
     * - l'appareil a redémarré (l'horloge monotone est repartie de zéro) ;
     * - il a été éteint (l'horloge murale a avancé bien plus que la monotone) ;
     * - une charge est passée inaperçue entre deux relevés (le niveau est remonté).
     */
    private fun breaksContinuity(
        previous: BatterySample,
        current: BatterySample,
        discharging: Boolean,
    ): Boolean {
        val wallDelta = current.at - previous.at
        val monotonicDelta = current.realtimeMs - previous.realtimeMs
        if (monotonicDelta < 0) return true
        if (wallDelta - monotonicDelta > POWERED_OFF_TOLERANCE_MS) return true
        if (wallDelta > MAX_GAP_MS) return true
        if (discharging && current.percent > previous.percent + LEVEL_RISE_TOLERANCE) return true
        return false
    }

    // ------------------------------------------------------------- moyennes

    /**
     * Autonomie moyenne extrapolée à une charge complète.
     *
     * Estimateur pondéré — temps total de décharge rapporté au pourcentage total
     * consommé — plutôt qu'une moyenne des extrapolations session par session :
     * une session de 3 % qui dure 20 minutes ne doit pas peser autant qu'une
     * décharge de 80 % sur deux jours.
     */
    fun averageFullLifeMs(sessions: List<DischargeSession>): Long? {
        if (sessions.size < 2) return null
        val totalMs = sessions.sumOf { it.durationMs }
        val totalDrained = sessions.sumOf { it.drainedPercent }
        if (totalDrained <= 0) return null
        return totalMs * 100L / totalDrained
    }

    /** Durée moyenne d'une charge complète, 0 % → 100 %. */
    fun averageFullChargeMs(sessions: List<ChargeSession>): Long? {
        if (sessions.isEmpty()) return null
        val totalMs = sessions.sumOf { it.durationMs }
        val totalGained = sessions.sumOf { it.gainedPercent }
        if (totalGained <= 0) return null
        return totalMs * 100L / totalGained
    }

    /**
     * Temps moyen entre deux branchements. Demande trois sessions : avec deux,
     * on n'aurait qu'un seul intervalle, et parler de « moyenne » serait abusif.
     */
    fun averageRechargeIntervalMs(sessions: List<DischargeSession>): Long? {
        if (sessions.size < 3) return null
        val plugIns = sessions.map { it.endAt }.sorted()
        val gaps = plugIns.zipWithNext { a, b -> b - a }.filter { it > 0 }
        if (gaps.isEmpty()) return null
        return gaps.sum() / gaps.size
    }

    // ------------------------------------------------------------ prévisions

    /**
     * Rythme de décharge courant, en points de pourcentage par heure, mesuré sur
     * la décharge en cours uniquement — un rythme moyenné sur plusieurs jours ne
     * dirait rien de l'usage du moment.
     */
    fun recentDrainPerHour(samples: List<BatterySample>, now: Long): Double? {
        val ordered = samples.sortedBy { it.at }
        val lastCharging = ordered.indexOfLast { it.charging }
        val window = ordered
            .drop(lastCharging + 1)
            .filter { it.at >= now - RECENT_WINDOW_MS }
        if (window.size < 2) return null

        val first = window.first()
        val last = window.last()
        val spanMs = last.at - first.at
        if (spanMs < MIN_RECENT_SPAN_MS) return null
        val drained = first.percent - last.percent
        if (drained <= 0) return null
        return drained / (spanMs / HOUR_MS)
    }

    /**
     * Assemble tout ce qui est affichable.
     *
     * [platformTimeToFullMs] est l'estimation du système (`computeChargeTimeRemaining`),
     * qui est la meilleure quand elle existe — mais beaucoup d'appareils renvoient
     * -1. On retombe alors sur le rythme de charge observé.
     */
    fun forecast(
        samples: List<BatterySample>,
        now: Long,
        currentPercent: Int,
        charging: Boolean,
        platformTimeToFullMs: Long? = null,
    ): Forecast {
        val discharges = dischargeSessions(samples)
        val fullLife = averageFullLifeMs(discharges)

        val timeToEmpty = if (charging) {
            null
        } else {
            val rate = recentDrainPerHour(samples, now)
            when {
                rate != null && rate > 0 -> ((currentPercent / rate) * HOUR_MS).toLong()
                // Pas encore de pente récente : l'autonomie moyenne fait un repli correct.
                fullLife != null -> fullLife * currentPercent / 100L
                else -> null
            }
        }

        val timeToFull = if (!charging || currentPercent >= 100) {
            null
        } else {
            platformTimeToFullMs?.takeIf { it > 0 }
                ?: averageFullChargeMs(chargeSessions(samples))
                    ?.let { it * (100 - currentPercent) / 100L }
        }

        return Forecast(
            averageFullLifeMs = fullLife,
            rechargeIntervalMs = averageRechargeIntervalMs(discharges),
            timeToEmptyMs = timeToEmpty?.takeIf { it > 0 },
            timeToFullMs = timeToFull?.takeIf { it > 0 },
            sessionCount = discharges.size,
        )
    }
}
