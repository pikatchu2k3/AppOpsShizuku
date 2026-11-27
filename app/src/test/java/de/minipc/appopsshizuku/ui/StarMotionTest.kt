package de.minipc.appopsshizuku.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Die Bewegung des Sternenhimmels: ruhig, begrenzt, deterministisch, ohne aufpoppende Sterne. */
class StarMotionTest {

    private val w = 1080f
    private val h = 2340f
    private val d = 3f

    private fun motion() = StarMotion(w, h, d)

    @Test fun ruhelageIstDerFruehereStatischeHimmel() {
        // Dieselbe Folge wie die alte StarfieldView: Random(42), x, y, Radius, Helligkeit
        val rnd = Random(42)
        val m = motion()
        for (i in 0 until m.count) {
            assertEquals(rnd.nextFloat() * w, m.x[i], 0f)
            assertEquals(rnd.nextFloat() * h, m.y[i], 0f)
            assertEquals((0.6f + rnd.nextFloat() * 1.4f) * d, m.radius[i], 0f)
            assertEquals(0.25f + rnd.nextFloat() * 0.6f, m.brightness[i], 0f)
        }
    }

    @Test fun sternanzahlWieFrueher() {
        assertEquals(((w * h) / (d * d * 2600f)).toInt().coerceIn(40, 260), motion().count)
        assertEquals(40, StarMotion.starCount(100f, 100f, 3f))
        assertEquals(260, StarMotion.starCount(5000f, 9000f, 1f))
    }

    @Test fun staticPoseStelltRuhelageWiederHer() {
        val m = motion()
        val x0 = m.x.copyOf(); val b0 = m.brightness.copyOf()
        m.update(123.0)
        assertFalse(x0.contentEquals(m.x))
        m.staticPose()
        assertTrue(x0.contentEquals(m.x))
        assertTrue(b0.contentEquals(m.brightness))
    }

    @Test fun sterneBleibenImBildUndHelligkeitIm0Bis1Bereich() {
        val m = motion()
        for (t in listOf(0.0, 0.5, 7.3, 60.0, 3600.0, 86_400.0 * 3, 1e7)) {
            m.update(t)
            for (i in 0 until m.count) {
                assertTrue("x $t", m.x[i] in 0f..w)
                assertTrue("y $t", m.y[i] in 0f..h)
                assertTrue("hell $t", m.brightness[i] in 0f..1f)
            }
        }
    }

    @Test fun sterneFunkelnAberImMittelBleibtDieHelligkeit() {
        val m = motion()
        val sumStatic = m.brightness.sum()
        var sum = 0.0
        val n = 400
        for (k in 0 until n) {
            m.update(30.0 + k * 0.7)
            // Randausblendung herausrechnen: nur Sterne weit weg vom Rand zaehlen
            sum += m.brightness.indices.filter { m.x[it] in 120f..(w - 120f) && m.y[it] in 120f..(h - 120f) }
                .sumOf { m.brightness[it].toDouble() } / m.brightness.indices.count {
                m.x[it] in 120f..(w - 120f) && m.y[it] in 120f..(h - 120f)
            }.coerceAtLeast(1)
        }
        val meanAnimated = sum / n
        val meanStatic = sumStatic / m.count
        // Gleiche Groessenordnung: die Animation macht den Himmel weder dunkler noch heller
        assertTrue("$meanAnimated vs $meanStatic", meanAnimated in meanStatic * 0.8..meanStatic * 1.2)
    }

    @Test fun helligkeitSchwanktTatsaechlich() {
        val m = motion()
        val lo = FloatArray(m.count) { 1f }; val hi = FloatArray(m.count)
        for (k in 0 until 600) {
            m.update(k * 0.5)
            for (i in 0 until m.count) {
                // nur Sterne in der Mitte: dort wirkt keine Randausblendung
                if (m.x[i] in 150f..(w - 150f) && m.y[i] in 150f..(h - 150f)) {
                    lo[i] = minOf(lo[i], m.brightness[i]); hi[i] = maxOf(hi[i], m.brightness[i])
                }
            }
        }
        val moving = (0 until m.count).count { hi[it] > 0f && hi[it] - lo[it] > 0.03f }
        assertTrue("zu wenige funkelnde Sterne: $moving", moving > m.count / 2)
    }

    @Test fun treibenIstLangsamUndNachObenGerichtet() {
        val m = motion()
        m.update(0.0)
        val y0 = m.y.copyOf()
        m.update(2.0)
        var up = 0; var maxMove = 0f
        for (i in 0 until m.count) {
            var dy = m.y[i] - y0[i]
            if (dy > h / 2) dy -= h; if (dy < -h / 2) dy += h        // Umbruch herausrechnen
            if (dy < 0) up++
            maxMove = maxOf(maxMove, kotlin.math.abs(dy))
        }
        assertTrue("fast alle nach oben", up > m.count * 0.9)
        // schnellster Stern: (0,8 + 2,6) dp/s * 1,2 = 4,08 dp/s -> in 2 s hoechstens ~8,2 dp
        assertTrue("zu schnell: $maxMove px", maxMove <= 8.3f * d)
    }

    @Test fun amRandWirdAusgeblendetStattAufzupoppen() {
        val m = motion()
        val band = StarMotion.EDGE_FADE_DP * d
        for (k in 0 until 200) {
            m.update(k * 1.7)
            for (i in 0 until m.count) {
                val edge = minOf(minOf(m.x[i], w - m.x[i]), minOf(m.y[i], h - m.y[i]))
                if (edge < 1f) assertTrue("am Rand fast unsichtbar", m.brightness[i] < 0.05f)
                if (edge < band * 0.2f) assertTrue(m.brightness[i] <= 0.35f)
            }
        }
    }

    @Test fun gleicherSeedGleicheBewegungAndererSeedAnders() {
        val a = StarMotion(w, h, d, 7L); val b = StarMotion(w, h, d, 7L); val c = StarMotion(w, h, d, 8L)
        a.update(99.0); b.update(99.0); c.update(99.0)
        assertTrue(a.x.contentEquals(b.x) && a.brightness.contentEquals(b.brightness))
        assertFalse(a.x.contentEquals(c.x))
    }

    @Test fun zeitSprungWieNeustartIstKonsistent() {
        // Zustandslos: update(t) haengt nicht davon ab, was davor berechnet wurde
        val a = motion(); val b = motion()
        for (k in 0 until 50) a.update(k * 0.033)
        a.update(500.0); b.update(500.0)
        assertTrue(a.x.contentEquals(b.x) && a.y.contentEquals(b.y) && a.brightness.contentEquals(b.brightness))
    }

    @Test fun lichtscheinAtmetInGrenzen() {
        val m = motion()
        var lo = 2f; var hi = 0f
        var t = 0.0
        while (t < 60) { val g = m.glow(t); lo = minOf(lo, g); hi = maxOf(hi, g); t += 0.1 }
        assertTrue(lo >= 0.84f && hi <= 1.0001f)
        assertTrue("atmet kaum", hi - lo > 0.1f)
    }

    // ---- Sternschnuppe ----

    private fun streaks(m: StarMotion, seconds: Int, stepMs: Int = 20): List<Pair<Double, Double>> {
        val s = StarMotion.Streak(); val out = mutableListOf<Pair<Double, Double>>()
        var begin = -1.0; var t = 0.0
        while (t < seconds) {
            m.streak(t, s)
            if (s.active && begin < 0) begin = t
            if (!s.active && begin >= 0) { out += begin to t; begin = -1.0 }
            t += stepMs / 1000.0
        }
        return out
    }

    @Test fun sternschnuppeKommtGelegentlichUndUeberlapptNie() {
        val ev = streaks(motion(), 1800)
        assertTrue("in 30 min mindestens 60 Sternschnuppen, waren ${ev.size}", ev.size in 60..150)
        for (k in 1 until ev.size) {
            val gap = ev[k].first - ev[k - 1].second
            assertTrue("Abstand $gap", gap in 10.0..37.0)
        }
        for ((a, b) in ev) assertTrue("Dauer ${b - a}", b - a in 0.8..1.15)
    }

    @Test fun sternschnuppeBlendetWeichEinUndAus() {
        val m = motion(); val s = StarMotion.Streak()
        val ev = streaks(m, 60).first()
        m.streak(ev.first + 0.03, s)
        assertTrue(s.alpha < 0.2f)
        m.streak((ev.first + ev.second) / 2, s)
        assertTrue("Mitte hell", s.alpha > 0.9f)
        m.streak(ev.second - 0.03, s)
        assertTrue(s.alpha < 0.2f)
    }

    @Test fun sternschnuppeBleibtImBildBereichUndHatEinenSchwanzHinterDemKopf() {
        val m = motion(); val s = StarMotion.Streak()
        for ((a, b) in streaks(m, 600)) {
            var t = a
            while (t < b) {
                m.streak(t, s)
                if (s.active) {
                    assertTrue(s.headX in -50f..(w + 50f) && s.headY in -50f..(h + 50f))
                    // Schwanz liegt links/oben vom Kopf (Flugrichtung nach rechts unten)
                    assertTrue(s.tailX <= s.headX + 0.01f && s.tailY <= s.headY + 0.01f)
                    val len = kotlin.math.hypot(s.headX - s.tailX, s.headY - s.tailY)
                    assertTrue("Schwanz zu lang: $len", len <= 190f * d)
                }
                t += 0.05
            }
        }
    }

    @Test fun sternschnuppeIstDeterministischUndNegativeZeitIstRuhig() {
        val a = StarMotion.Streak(); val b = StarMotion.Streak()
        val m1 = motion(); val m2 = motion()
        for (t in listOf(25.0, 26.1, 49.5, 73.2)) {
            m1.streak(t, a); m2.streak(t, b)
            assertEquals(a.active, b.active); assertEquals(a.headX, b.headX, 0f); assertEquals(a.alpha, b.alpha, 0f)
        }
        m1.streak(-5.0, a)
        assertFalse(a.active)
    }

    @Test fun ueberLangeLaufzeitKeinGenauigkeitsverlust() {
        // 30 Tage Dauerbetrieb: Positionen bleiben gueltig
        val m = motion()
        m.update(30.0 * 86_400)
        for (i in 0 until m.count) assertTrue(m.x[i] in 0f..w && m.y[i] in 0f..h)
    }
}
