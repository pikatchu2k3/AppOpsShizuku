package de.minipc.appopsshizuku.appops

import de.minipc.appopsshizuku.appops.OnDemandCallers.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class OnDemandCallersTest {
    private val own = "de.minipc.appopsshizuku"
    private val dialer = "de.minipc.appdialer.nxt"
    private val target = "com.alibaba.aliexpresshd"

    @Test fun unbekannterAufruferWirdAbgelehnt() {
        assertEquals(Decision.DENY, OnDemandCallers.decide(null, own, target, setOf(dialer)))
        assertEquals(Decision.DENY, OnDemandCallers.decide("  ", own, target, setOf(dialer)))
    }

    @Test fun eigeneAppDarfImmer() = assertEquals(Decision.ALLOW, OnDemandCallers.decide(own, own, target, emptySet()))

    @Test fun erlaubterStarterDarf() = assertEquals(Decision.ALLOW, OnDemandCallers.decide(dialer, own, target, setOf(dialer)))

    @Test fun neuerStarterMussGefragtWerden() = assertEquals(Decision.ASK, OnDemandCallers.decide(dialer, own, target, emptySet()))

    @Test fun zielAppDarfSichNichtSelbstFreigeben() =
        assertEquals(Decision.DENY, OnDemandCallers.decide(target, own, target, setOf(target)))
}
