package de.minipc.appopsshizuku.appops

import org.json.JSONObject

/**
 * Android-freies, defensives JSON-Parsen fuer Privacy-Profile.
 *
 * Der Crash in v1.12.4 kam von JSONObject(null/leer) auf dem Main-Thread.
 * [parse] liefert bei null/leer/kaputt IMMER ein leeres JSONObject und wirft nie.
 */
object ProfileJson {

    fun parse(raw: String?): JSONObject {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return JSONObject()
        return runCatching { JSONObject(trimmed) }.getOrElse { JSONObject() }
    }
}
