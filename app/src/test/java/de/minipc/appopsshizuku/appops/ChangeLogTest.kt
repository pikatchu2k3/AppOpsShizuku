package de.minipc.appopsshizuku.appops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeLogTest {

    private fun op(id: Long) = ChangeLog.Entry(id, id, ChangeLog.Type.OP, "l", "pkg", "CAMERA", "allow")

    @Test
    fun neuesterVorneUndBegrenzt() {
        var list = emptyList<ChangeLog.Entry>()
        for (i in 1..5) list = ChangeLog.prepend(list, op(i.toLong()), max = 3)
        assertEquals(listOf(5L, 4L, 3L), list.map { it.id })
    }

    @Test
    fun jsonRoundtripOpNetInfo() {
        val entries = listOf(
            ChangeLog.Entry(1, 10, ChangeLog.Type.OP, "op", "pkg", "CAMERA", "deny"),
            ChangeLog.Entry(2, 20, ChangeLog.Type.NET, "net", "pkg", netSwitch = "OFFLINE", prevOn = false),
            ChangeLog.Entry(3, 30, ChangeLog.Type.INFO, "info")
        )
        for (e in entries) assertEquals(e, ChangeLog.fromJson(ChangeLog.toJson(e)))
    }

    @Test
    fun nurOpUndNetSindUmkehrbar() {
        assertTrue(op(1).undoable)
        assertTrue(ChangeLog.Entry(1, 1, ChangeLog.Type.NET, "l", "p", netSwitch = "BG_DATA", prevOn = true).undoable)
        assertEquals(false, ChangeLog.Entry(1, 1, ChangeLog.Type.INFO, "l").undoable)
    }

    @Test
    fun kaputtesJsonGibtNull() {
        assertNull(ChangeLog.fromJson(org.json.JSONObject("""{"id":1}""")))
    }
}
