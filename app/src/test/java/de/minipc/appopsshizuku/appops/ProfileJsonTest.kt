package de.minipc.appopsshizuku.appops

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Defensives JSON-Parsen: null/leer/kaputt -> leeres JSONObject, NIE eine Exception
 * (der v1.12.4-Crash kam von JSONObject(null/leer) auf dem Main-Thread).
 */
class ProfileJsonTest {

    @Test fun null_liefertLeeresObjekt() =
        assertEquals(0, ProfileJson.parse(null).length())

    @Test fun leererString_liefertLeeresObjekt() =
        assertEquals(0, ProfileJson.parse("").length())

    @Test fun nurWhitespace_liefertLeeresObjekt() =
        assertEquals(0, ProfileJson.parse("   \n ").length())

    @Test fun kaputtesJson_liefertLeeresObjekt() =
        assertEquals(0, ProfileJson.parse("{ kaputt").length())

    @Test fun keinObjekt_sondernArray_liefertLeeresObjekt() =
        assertEquals(0, ProfileJson.parse("[1,2,3]").length())

    @Test fun gueltigesJson_wirdGeparst() {
        val obj = ProfileJson.parse("""{"CAMERA":"deny","RECORD_AUDIO":"allow"}""")
        assertEquals("deny", obj.getString("CAMERA"))
        assertEquals("allow", obj.getString("RECORD_AUDIO"))
    }
}
