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

class OnDemandCallersPinTest {
    private val own = "de.minipc.appopsshizuku"
    private val dialer = "de.minipc.appdialer.nxt"
    private val target = "com.example.app"
    private val fp = "aa11"

    @Test fun altbestandOhneSignaturWirdErlaubtUndSpaeterGepinnt() {
        assertEquals(Decision.ALLOW, OnDemandCallers.decide(dialer, own, target, setOf(dialer), fp))
        val pinned = OnDemandCallers.pin(setOf(dialer, "other.app"), dialer, fp)
        assertEquals(setOf("other.app", "$dialer@$fp"), pinned)
    }

    @Test fun gepinnterEintragBrauchtGleicheSignatur() {
        val t = setOf("$dialer@$fp")
        assertEquals(Decision.ALLOW, OnDemandCallers.decide(dialer, own, target, t, fp))
        // andere App unter gleichem Paketnamen: neu fragen
        assertEquals(Decision.ASK, OnDemandCallers.decide(dialer, own, target, t, "bb22"))
        // Signatur nicht lesbar: nicht still durchlassen
        assertEquals(Decision.ASK, OnDemandCallers.decide(dialer, own, target, t, null))
    }

    @Test fun erneutesPinnenErsetztDenAlteintrag() {
        val t = OnDemandCallers.pin(setOf("$dialer@old"), dialer, "new")
        assertEquals(setOf("$dialer@new"), t)
        assertEquals(1, OnDemandCallers.count(t))
    }

    @Test fun entryForOhneSignaturIstNurDerPaketname() {
        assertEquals(dialer, OnDemandCallers.entryFor(dialer, null))
        assertEquals("$dialer@$fp", OnDemandCallers.entryFor(dialer, fp))
    }
}
