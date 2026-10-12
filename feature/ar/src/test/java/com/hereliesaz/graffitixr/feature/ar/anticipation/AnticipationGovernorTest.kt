package com.hereliesaz.graffitixr.feature.ar.anticipation

import com.hereliesaz.graffitixr.common.model.AnticipationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [AnticipationSettings], its throttled subsets and the governor that picks them. */
class AnticipationGovernorTest {

    private val max = AnticipationSettings(distance = 3, latitude = 8, longitude = 3, roll = 4)

    private fun sites(s: AnticipationSettings) = buildList {
        for (shell in 0 until s.distance) for (aim in 0 until s.aimsPerSide(shell).let { it * it }) for (k in 0 until s.roll) {
            add(HalfSphere.Site(shell, 0, 0, k, aim))
            for (c in 1..s.longitude) for (m in 0 until s.latitude) add(HalfSphere.Site(shell, c, m, k, aim))
        }
    }

    @Test
    fun `views are every aim of every half-sphere times the pole plus every cut, times roll, and settings clamp`() {
        // Aims per side: 3 at a third of the capture distance, 2 at two thirds, 1 at capture.
        assertEquals(listOf(3, 2, 1), (0 until 3).map { max.aimsPerSide(it) })
        assertEquals(listOf(6, 3, 2, 2, 2, 1), (0 until 6).map { AnticipationSettings(distance = 6).aimsPerSide(it) })
        assertEquals((9 + 4 + 1) * (1 + 8 * 3) * 4, max.maxViews)
        assertEquals(max.maxViews, sites(max).size)
        assertEquals(0, AnticipationSettings(distance = 0).maxViews)
        val wild = AnticipationSettings(99, -1, 99, 0, Float.NaN, 2f, -1f).sanitized()
        assertEquals(AnticipationSettings(AnticipationSettings.MAX_DISTANCE, 0, AnticipationSettings.MAX_LONGITUDE, 1, AnticipationSettings.DEFAULT_BATTERY_WEIGHT, 1f, 0f), wild)
    }

    @Test
    fun `throttled subsets are nested, from full to none`() {
        val chain = AnticipationDecimation.chain(max)
        assertEquals(AnticipationDecimation.FULL, chain.first())
        assertEquals(AnticipationDecimation.NONE, chain.last())
        assertEquals(max.maxViews, chain.first().views(max))
        val all = sites(max)
        for (i in 1 until chain.size) {
            val kept = all.filter { chain[i].keeps(it, max) }
            assertTrue(kept.all { chain[i - 1].keeps(it, max) })
            assertTrue(kept.size < all.count { chain[i - 1].keeps(it, max) })
            assertEquals(chain[i].toString(), chain[i].views(max), kept.size)
        }
        // Far half-spheres go first, outermost in; the nearest stays whole until it is the only one left.
        assertEquals(listOf(Int.MAX_VALUE, 2, 1), chain.take(3).map { it.shells })
        assertTrue(chain.take(3).all { it.circleStep == 1 && it.meridianStep == 1 && it.rollStep == 1 })
        assertTrue(chain.drop(2).dropLast(1).all { it.shells == 1 })
        // Every kept half-sphere keeps all its aims; the nearest one's upright poles are the last views standing.
        for (d in chain.dropLast(1)) for (shell in 0 until max.distance) {
            val aims = all.filter { it.shell == shell && d.keeps(it, max) }.map { it.aim }.toSet()
            assertTrue(aims.isEmpty() || aims.size == max.aimsPerSide(shell).let { it * it })
        }
        val last = chain[chain.size - 2]
        assertEquals((0 until 9).map { HalfSphere.Site(0, 0, 0, 0, it) }, all.filter { last.keeps(it, max) })
    }

    @Test
    fun `a factor picks the largest subset that fits`() {
        assertEquals(AnticipationDecimation.FULL, AnticipationDecimation.forFactor(max, 1f))
        assertEquals(AnticipationDecimation.NONE, AnticipationDecimation.forFactor(max, 0f))
        var previous = Int.MAX_VALUE
        for (f in listOf(0.9f, 0.6f, 0.4f, 0.2f, 0.05f)) {
            val views = AnticipationDecimation.forFactor(max, f).views(max)
            assertTrue("$f: $views", views <= f * max.maxViews && views <= previous)
            previous = views
        }
    }

    private class Source(var pressure: DevicePressure = DevicePressure.NONE) : DevicePressureSource {
        override fun read() = pressure
    }

    @Test
    fun `heat throttles at once, relief waits, and the tracker's own load counts`() {
        val source = Source()
        val g = AnticipationGovernor(max.copy(batteryWeight = 0.5f, heatWeight = 1f, computeWeight = 1f), source)
        assertEquals(AnticipationDecimation.FULL, g.update(0).decimation)
        source.pressure = DevicePressure(battery = 0f, heat = 1f)
        assertEquals(AnticipationDecimation.FULL, g.update(1_000).decimation) // not yet re-read
        assertEquals(AnticipationDecimation.NONE, g.update(2_000).decimation)
        assertEquals(1f, g.status.heatPressure)
        // Cooled: held for 10 s first.
        source.pressure = DevicePressure.NONE
        assertEquals(AnticipationDecimation.NONE, g.update(4_000).decimation)
        assertEquals(AnticipationDecimation.NONE, g.update(12_000).decimation)
        assertEquals(AnticipationDecimation.FULL, g.update(14_000).decimation)
        // Half-empty battery at weight ½: a quarter cut.
        source.pressure = DevicePressure(battery = 0.5f, heat = 0f)
        val s = g.update(16_000)
        assertEquals(0.75f, s.factor, 1e-6f)
        assertTrue(s.views <= 0.75f * max.maxViews)
        // Frames taking their whole interval: full compute pressure.
        var t = 0L
        repeat(60) { g.onFrame(33f, t); t += 33_000_000L }
        source.pressure = DevicePressure.NONE
        val busy = g.update(18_000)
        assertTrue("${busy.computePressure}", busy.computePressure > 0.9f)
        assertTrue("${busy.views}", busy.views <= 0.05f * max.maxViews)
        // New settings apply at the next update; zero weights ignore every pressure.
        g.settings = AnticipationSettings(1, 4, 1, 1, 0f, 0f, 0f)
        assertEquals(1, g.update(18_500).max.distance)
        assertEquals(AnticipationDecimation.FULL, g.update(30_000).decimation)
    }
}
