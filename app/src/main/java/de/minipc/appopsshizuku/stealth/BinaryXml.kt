package de.minipc.appopsshizuku.stealth

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parser/Editor für binäres Android-XML (AXML).
 *
 * Ansatz: Chunk-Surgery statt Voll-Dekodierung. Der String-Pool bleibt unangetastet,
 * entfernt werden nur komplette <uses-permission>-Elemente (Start- + End-Tag).
 * Alle Chunks sind selbstbeschreibend (Größenfelder) und enthalten keine absoluten
 * Offsets auf spätere Chunks → nach dem Entfernen muss nur die Dateigröße im
 * 8-Byte-Header (Typ 0x0003) aktualisiert werden. Unbenutzte String-Pool-Einträge
 * sind harmlos.
 */
object BinaryXml {

    private const val TYPE_STRING_POOL = 0x0001
    private const val TYPE_RESOURCE_MAP = 0x0180
    private const val TYPE_START_NS = 0x0100
    private const val TYPE_END_NS = 0x0101
    private const val TYPE_START_ELEMENT = 0x0102
    private const val TYPE_END_ELEMENT = 0x0103
    private const val TYPE_CDATA = 0x0104

    private const val NO_INDEX = -1 // 0xFFFFFFFF

    private val PERMISSION_ELEMENTS = setOf(
        "uses-permission",
        "uses-permission-sdk-23",
        "uses-permission-sdk-33"
    )

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    private const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"

    // ------------------------------------------------------------------
    // Öffentliche API
    // ------------------------------------------------------------------

    /** Liest alle <uses-permission>-Deklarationen aus einem AXML-Manifest (dedupliziert). */
    fun permissions(data: ByteArray): List<String> {
        val parsed = parse(data) ?: return emptyList()
        val result = LinkedHashSet<String>()
        for (c in parsed.chunks) {
            if (c.type != TYPE_START_ELEMENT) continue
            if (elementName(parsed.pool, c) in PERMISSION_ELEMENTS) {
                permissionValue(parsed.pool, c)?.let { result.add(it) }
            }
        }
        return result.toList()
    }

    /** Liest den Paketnamen (package-Attribut des Manifest-Roots). */
    fun packageName(data: ByteArray): String? {
        val parsed = parse(data) ?: return null
        for (c in parsed.chunks) {
            if (c.type != TYPE_START_ELEMENT) continue
            val name = elementName(parsed.pool, c)
            return if (name == "manifest") attributeValue(parsed.pool, c, "package") else null
        }
        return null
    }

    /** Entfernt alle <uses-permission>-Elemente, deren Name in [toRemove] liegt. */
    fun removePermissions(data: ByteArray, toRemove: Set<String>): ByteArray {
        if (toRemove.isEmpty()) return data
        val parsed = parse(data) ?: return data
        if (parsed.pool.isEmpty) return data

        val out = ByteArrayOutputStream()
        var skipDepth = 0
        for (c in parsed.chunks) {
            if (skipDepth > 0) {
                when (c.type) {
                    TYPE_START_ELEMENT -> skipDepth++
                    TYPE_END_ELEMENT -> skipDepth--
                }
                continue
            }
            if (c.type == TYPE_START_ELEMENT &&
                elementName(parsed.pool, c) in PERMISSION_ELEMENTS
            ) {
                val perm = permissionValue(parsed.pool, c)
                if (perm != null && perm in toRemove) {
                    skipDepth = 1
                    continue
                }
            }
            out.write(c.bytes)
        }
        if (skipDepth != 0) return data // Struktur unerwartet → unverändert lassen

        val newSize = 8 + out.size()
        val result = ByteArray(newSize)
        writeU16(result, 0, 0x0003)
        writeU16(result, 2, 0x0008)
        writeInt(result, 4, newSize)
        System.arraycopy(out.toByteArray(), 0, result, 8, out.size())
        return result
    }

    /**
     * Fuegt ein <uses-permission android:name="[permission]"/> als erstes Kind
     * des <manifest>-Elements ein (falls noch nicht vorhanden).
     * Noetig z.B. fuer ACCESS_NETWORK_STATE beim VPN-Guard: Der VpnGuard ruft
     * ConnectivityManager-APIs auf, die ohne diese Permission eine
     * SecurityException werfen (real getroffen 08/2026 auf FOSS Browser).
     */
    fun addPermission(data: ByteArray, permission: String): ByteArray {
        val parsed = parse(data) ?: return data
        if (parsed.pool.isEmpty) return data
        // Schon vorhanden?
        if (permissions(data).any { it == permission }) return data

        // 1. String in den Pool (falls nicht da)
        var poolBytes = parsed.pool.raw
        var permIdx = parsed.pool.indexOf(permission)
        if (permIdx < 0) {
            val appended = appendPoolString(poolBytes, permission) ?: return data
            poolBytes = appended.first
            permIdx = appended.second
        }
        // "uses-permission"-Element-Name + android-Namespace-URI Indizes
        var elemIdx = parsed.pool.indexOf("uses-permission")
        var nsIdx = parsed.pool.indexOf(ANDROID_NS)
        if (elemIdx < 0 || nsIdx < 0) {
            val a1 = appendPoolString(poolBytes, "uses-permission") ?: return data
            poolBytes = a1.first; elemIdx = a1.second
            val a2 = appendPoolString(poolBytes, ANDROID_NS) ?: return data
            poolBytes = a2.first; nsIdx = a2.second
        }
        // "name"-String-Index fuer das Attribut android:name sicherstellen
        var nameAttrIdx = parsed.pool.indexOf("name")
        if (nameAttrIdx < 0) {
            val a3 = appendPoolString(poolBytes, "name") ?: return data
            poolBytes = a3.first; nameAttrIdx = a3.second
        }
        // ResourceMap-Eintrag fuer android:name (0x01010003) sicherstellen.
        // appendResourceMapEntry liefert null, wenn der Index SCHON in der Map
        // liegt — "name" (0x01010003) existiert in aapt2-Manifesten fast immer,
        // dann ist nichts zu tun.
        var resMapBytes: ByteArray? = null
        var resMapIdx = -1
        val nameId = 0x01010003 // android.R.attr.name
        for (i in parsed.chunks.indices) {
            if (parsed.chunks[i].type == TYPE_RESOURCE_MAP) {
                val buf = java.nio.ByteBuffer.wrap(parsed.chunks[i].bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val entries = (buf.getInt(4) - 8) / 4
                if (nameAttrIdx >= entries) {
                    val r = appendResourceMapEntry(parsed.chunks[i].bytes, nameAttrIdx, nameId)
                    if (r == null) return data
                    resMapBytes = r
                    resMapIdx = i
                }
                break
            }
        }

        // 2. Neue Chunks bauen: Start-Element <uses-permission> + End-Element
        // Start-Element: Header(16) + ns/name/attrStart/attrSize/attrCount/id/class/style(20) + 1 Attribut(20)
        val startChunk = ByteArray(16 + 20 + 20)
        writeU16(startChunk, 0, TYPE_START_ELEMENT)
        writeU16(startChunk, 2, 16) // headerSize: Element-Chunks haben 16-Byte-Node-Header!
        writeInt(startChunk, 4, startChunk.size)
        writeInt(startChunk, 8, 0) // lineNumber
        writeInt(startChunk, 12, -1) // comment
        writeInt(startChunk, 16, NO_INDEX) // Element-ns: uses-permission steht im DEFAULT-Namespace (kein android:-PraeFix!)
        writeInt(startChunk, 20, elemIdx)
        writeU16(startChunk, 24, 20) // attrStart (nach 16+20)
        writeU16(startChunk, 26, 20) // attrSize
        writeU16(startChunk, 28, 1)  // attrCount
        writeU16(startChunk, 30, -1) // idIndex
        writeU16(startChunk, 32, -1) // classIndex
        writeU16(startChunk, 34, -1) // styleIndex
        // Attribut: ns=android, name="name"-Index (Attributname!), rawValue=
        // Permission-String-Index (aapt2-Konvention; permissionValue liest den
        // Wert aus rawValue), Res_value (Typ STRING, data = String-Index)
        val attrBase = 36
        writeInt(startChunk, attrBase, nsIdx)
        writeInt(startChunk, attrBase + 4, nameAttrIdx)
        writeInt(startChunk, attrBase + 8, permIdx) // rawValue = Permission-String
        startChunk[attrBase + 12] = 8
        startChunk[attrBase + 13] = 0
        startChunk[attrBase + 14] = 0
        startChunk[attrBase + 15] = 0x03 // TYPE_STRING
        writeInt(startChunk, attrBase + 16, permIdx)

        val endChunk = ByteArray(24)
        writeU16(endChunk, 0, TYPE_END_ELEMENT)
        writeU16(endChunk, 2, 16) // headerSize: Node-Header ist 16 Bytes
        writeInt(endChunk, 4, endChunk.size)
        writeInt(endChunk, 8, 0)
        writeInt(endChunk, 12, -1)
        writeInt(endChunk, 16, NO_INDEX) // Element-ns: Default-Namespace
        writeInt(endChunk, 20, elemIdx)

        // 3. Chunks zusammensetzen: Pool ersetzen, ResourceMap-Erweiterung
        //    einsetzen, neue Elemente nach dem manifest-Start-Tag einfuegen
        val out = ByteArrayOutputStream()
        var inserted = false
        for ((i, c) in parsed.chunks.withIndex()) {
            val isPool = c.type == 0x0001 // String-Pool-Chunk
            when {
                isPool -> out.write(poolBytes)
                i == resMapIdx && resMapBytes != null -> out.write(resMapBytes)
                else -> out.write(c.bytes)
            }
            if (!inserted && c.type == TYPE_START_ELEMENT &&
                elementName(parsed.pool, c) == "manifest"
            ) {
                out.write(startChunk)
                out.write(endChunk)
                inserted = true
            }
        }
        if (!inserted) return data

        val newSize = 8 + out.size()
        val result = ByteArray(newSize)
        writeU16(result, 0, 0x0003)
        writeU16(result, 2, 0x0008)
        writeInt(result, 4, newSize)
        System.arraycopy(out.toByteArray(), 0, result, 8, out.size())
        return result
    }

    /**
     * Entfernt alle <category android:name="android.intent.category.LAUNCHER"/>
     * Elemente (Stealth: Launcher-Icon ausblenden). App bleibt installiert und
     * ueber andere Einstiege (Deep-Links, unsere App) startbar.
     */
    fun removeCategoryLauncher(data: ByteArray): ByteArray {
        val parsed = parse(data) ?: return data
        if (parsed.pool.isEmpty) return data

        val out = ByteArrayOutputStream()
        var i = 0
        val chunks = parsed.chunks
        while (i < chunks.size) {
            val c = chunks[i]
            if (c.type == TYPE_START_ELEMENT &&
                elementName(parsed.pool, c) == "category" &&
                permissionValue(parsed.pool, c) == CATEGORY_LAUNCHER
            ) {
                // self-closing <category .../> -> Start- + direkt folgendes End-Tag entfernen
                i += 2
                continue
            }
            out.write(c.bytes)
            i++
        }

        val newSize = 8 + out.size()
        val result = ByteArray(newSize)
        writeU16(result, 0, 0x0003)
        writeU16(result, 2, 0x0008)
        writeInt(result, 4, newSize)
        System.arraycopy(out.toByteArray(), 0, result, 8, out.size())
        return result
    }

    /**
     * Setzt android:excludeFromRecents="true" auf der Launcher-Activity (der Activity
     * mit MAIN/LAUNCHER-Intent-Filter). Das Attribut existiert danach dauerhaft im
     * Manifest -> die App erscheint nie im Verlauf (Recents), egal wie sie gestartet wird.
     *
     * AXML-Eingriffe:
     * 1. String "excludeFromRecents" in den String-Pool aufnehmen (falls fehlt)
     * 2. ResourceMap um die Ressourcen-ID 0x010034a3 erweitern (Attribut-Aufloesung)
     * 3. Attribut (ns=android, name=excludeFromRecents, TYPE_INT_BOOLEAN data=1) an die
     *    Launcher-Activity haengen (Chunk waechst um 20 Bytes; Datei-Header-Groesse anpassen)
     */
    fun setExcludeFromRecents(data: ByteArray): ByteArray {
        val parsed = parse(data) ?: return data
        if (parsed.pool.isEmpty) return data

        val nsIndex = parsed.pool.indexOf(ANDROID_NS)
        if (nsIndex < 0) return data

        // String-Index fuer "excludeFromRecents" sicherstellen
        var poolBytes = parsed.pool.raw
        var nameIndex = parsed.pool.indexOf("excludeFromRecents")
        if (nameIndex < 0) {
            val appended = appendPoolString(poolBytes, "excludeFromRecents") ?: return data
            poolBytes = appended.first
            nameIndex = appended.second
        }

        // Launcher-Activity-Chunk finden
        val launcherIdx = findLauncherActivityChunkIndex(parsed.chunks, parsed.pool) ?: return data
        val launcherChunk = parsed.chunks[launcherIdx]

        // Attribut setzen/hinzufuegen (Chunk-Bytes)
        // resIds: ResourceMap als IntArray (String-Index -> resId) fuer die
        // sortierte Einfuegeposition (aapt2-Reihenfolge, real verifiziert 08/2026)
        val resIds = parsed.chunks.firstOrNull { it.type == TYPE_RESOURCE_MAP }?.let { cm ->
            val count = (cm.bytes.size - 8) / 4
            IntArray(count) { i ->
                java.nio.ByteBuffer.wrap(cm.bytes, 8 + i * 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            }
        }
        val newLauncherBytes = addExcludeAttr(launcherChunk.bytes, nsIndex, nameIndex, resIds)
        val launcherDelta = newLauncherBytes.size - launcherChunk.bytes.size

        // ResourceMap erweitern (falls Attributname neu ist)
        var resMapBytes: ByteArray? = null
        var resMapIdx = -1
        if (parsed.pool.indexOf("excludeFromRecents") < 0) {
            for (i in parsed.chunks.indices) {
                if (parsed.chunks[i].type == TYPE_RESOURCE_MAP) {
                    resMapBytes = appendResourceMapEntry(parsed.chunks[i].bytes, nameIndex, EXCLUDE_FROM_RECENTS_ID)
                        ?: return data
                    resMapIdx = i
                    break
                }
            }
            if (resMapBytes == null) return data // keine ResourceMap -> Abbruch (untypisch)
        }

        // Datei neu zusammensetzen
        val out = ByteArrayOutputStream()
        var total = 8
        for (i in parsed.chunks.indices) {
            val c = parsed.chunks[i]
            val bytes = when (i) {
                0 -> poolBytes
                resMapIdx -> resMapBytes!!
                launcherIdx -> newLauncherBytes
                else -> c.bytes
            }
            out.write(bytes)
            total += bytes.size
        }
        val result = ByteArray(total)
        writeU16(result, 0, 0x0003)
        writeU16(result, 2, 0x0008)
        writeInt(result, 4, total)
        System.arraycopy(out.toByteArray(), 0, result, 8, out.size())
        return result
    }

    // WICHTIG (08/2026, OnePlus-Live-Befund): Das ist die ECHTE public Framework-ID
    // fuer android.R.attr.excludeFromRecents (0x01010017), verifiziert via
    // android.jar + aapt2 link. Vorher stand hier 0x010034A3 (externe KI-Empfehlung,
    // falsch!) — AOSP-Parser tolerierte es zufaellig, aber OxygenOS/ColorOS-PMS
    // erkennt die ID nicht -> Attribut still ignoriert -> App erschien im Verlauf.
    private const val EXCLUDE_FROM_RECENTS_ID = 0x01010017
    private const val TYPE_INT_BOOLEAN = 0x12
    private const val ACTION_MAIN = "android.intent.action.MAIN"

    /** Fuegt einen String am Ende des String-Pools an -> (neue Pool-Bytes, neuer Index). */
    private fun appendPoolString(poolBytes: ByteArray, s: String): Pair<ByteArray, Int>? {
        if (poolBytes.size < 28) return null
        val buf = ByteBuffer.wrap(poolBytes).order(ByteOrder.LITTLE_ENDIAN)
        val stringCount = buf.getInt(8)
        val styleCount = buf.getInt(12)
        val flags = buf.getInt(16)
        val stringsStart = buf.getInt(20)
        val stylesStart = buf.getInt(24)
        if (styleCount > 0) return null // Styles wuerden verschoben — nicht unterstuetzt (Manifeste: selten)
        if (stringsStart < 28 + (stringCount + styleCount) * 4) return null

        val utf8 = flags and 0x100 != 0
        val dataLen = poolBytes.size - stringsStart
        val oldData = poolBytes.copyOfRange(stringsStart, poolBytes.size)

        // Neuen String kodieren (Android-Format)
        val sb = java.io.ByteArrayOutputStream()
        if (utf8) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            // charLen (UTF-16-Laenge) + byteLen als varint
            writeLen8(sb, s.length)
            writeLen8(sb, bytes.size)
            sb.write(bytes)
            sb.write(0)
        } else {
            val chars = s.toCharArray()
            writeLen16(sb, chars.size)
            for (c in chars) {
                sb.write(c.code and 0xFF)
                sb.write((c.code shr 8) and 0xFF)
            }
            sb.write(0); sb.write(0)
        }
        // 4-Byte-Alignment
        while (sb.size() % 4 != 0) sb.write(0)
        val newString = sb.toByteArray()

        val newCount = stringCount + 1
        val newStringsStart = 28 + newCount * 4 + styleCount * 4
        val newSize = newStringsStart + dataLen + newString.size
        val result = ByteArray(newSize)
        writeU16(result, 0, 0x0001)
        writeU16(result, 2, 28)
        writeInt(result, 4, newSize)
        writeInt(result, 8, newCount)
        writeInt(result, 12, styleCount)
        writeInt(result, 16, flags)
        writeInt(result, 20, newStringsStart)
        writeInt(result, 24, stylesStart)
        // alte Offsets (relativ zu stringsStart) bleiben gueltig
        System.arraycopy(poolBytes, 28, result, 28, stringCount * 4)
        // neuer Offset
        writeInt(result, 28 + stringCount * 4, dataLen)
        // Styles-Offsets (keine)
        // Daten
        System.arraycopy(oldData, 0, result, newStringsStart, dataLen)
        System.arraycopy(newString, 0, result, newStringsStart + dataLen, newString.size)
        return result to newCount - 1
    }

    private fun writeLen8(out: java.io.ByteArrayOutputStream, v: Int) {
        if (v and 0x7F80 != 0) {
            out.write((v shr 8) or 0x80)
            out.write(v and 0xFF)
        } else {
            out.write(v)
        }
    }

    private fun writeLen16(out: java.io.ByteArrayOutputStream, v: Int) {
        // UTF-16-Stringformat: Laenge als 16-Bit-LE (immer 2 Bytes).
        // Extended-Form (>= 0x8000): 0x8000|(len>>16) + len&0xFFFF
        if (v >= 0x8000) {
            out.write((0x8000 or (v shr 16)) and 0xFF)
            out.write(((0x8000 or (v shr 16)) shr 8) and 0xFF)
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        } else {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
    }

    /** Erweitert die ResourceMap bis Index [nameIndex] und setzt dort [resId]. */
    private fun appendResourceMapEntry(resMapBytes: ByteArray, nameIndex: Int, resId: Int): ByteArray? {
        if (resMapBytes.size < 8) return null
        val buf = ByteBuffer.wrap(resMapBytes).order(ByteOrder.LITTLE_ENDIAN)
        val chunkSize = buf.getInt(4)
        val entries = (chunkSize - 8) / 4
        if (entries > nameIndex) return null // Eintrag existiert bereits
        val fill = nameIndex - entries
        val newSize = chunkSize + (fill + 1) * 4
        val result = ByteArray(newSize)
        System.arraycopy(resMapBytes, 0, result, 0, chunkSize)
        writeInt(result, 4, newSize)
        // fehlende Eintraege mit 0 (keine Ressourcen-ID) auffuellen
        for (i in 0 until fill) writeInt(result, 8 + (entries + i) * 4, 0)
        writeInt(result, 8 + nameIndex * 4, resId)
        return result
    }

    /** Fuegt das excludeFromRecents-Attribut zum Start-Element hinzu bzw. setzt data=true.
     *  [resIds] ist die ResourceMap (String-Index -> resId) fuer die sortierte Einfuegeposition. */
    private fun addExcludeAttr(chunk: ByteArray, nsIndex: Int, nameIndex: Int, resIds: IntArray? = null): ByteArray {
        val buf = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
        val chunkSize = buf.getInt(4)
        val attrStart = buf.getShort(24).toInt() and 0xFFFF
        val attrSize = buf.getShort(26).toInt() and 0xFFFF
        val attrCount = buf.getShort(28).toInt() and 0xFFFF
        val attrsBase = 16 + attrStart
        if (attrSize < 20) return chunk

        // Existiert das Attribut schon? -> nur dataType/data setzen
        for (i in 0 until attrCount) {
            val base = attrsBase + i * attrSize
            if (base + 20 > chunk.size) break
            val n = buf.getInt(base + 4)
            if (n == nameIndex) {
                val copy = chunk.copyOf()
                // Res_value: size=u16(2B) res0(1B) dataType(1B) data(4B)
                copy[base + 12] = 8
                copy[base + 13] = 0
                copy[base + 14] = 0
                copy[base + 15] = TYPE_INT_BOOLEAN.toByte()
                copy[base + 16] = -1  // Boolean true = 0xFFFFFFFF (wie aapt2!)
                copy[base + 17] = -1
                copy[base + 18] = -1
                copy[base + 19] = -1
                return copy
            }
        }

        // Neues Attribut EINFUEGEN an der nach Ressourcen-ID sortierten Position
        // (aapt2 sortiert Activity-Attribute aufsteigend nach resId: name(0x03),
        // exported(0x10), excludeFromRecents(0x17), launchMode(0x1d), ...).
        // WICHTIG (08/2026, Emulator-Isolation): Ein Anhaengen ans Chunk-Ende
        // (Position 8 von 9) fuehrt dazu, dass der System-PackageParser das
        // Attribut NICHT uebernimmt (EXCL=false) — real bewiesen mit Mini5b
        // (aapt2-Referenz: EFR an Position 2 von 11 funktioniert).
        val newSize = chunk.size + attrSize
        val result = ByteArray(newSize)
        System.arraycopy(chunk, 0, result, 0, chunk.size)
        writeInt(result, 4, newSize) // chunkSize
        writeU16(result, 28, attrCount + 1) // attributeCount

        // Insert-Position bestimmen: nach allen Attributen, deren resId < EFR-ID
        // (bzw. nach dem letzten Attribut mit kleinerer ID). Fallback: Ende.
        // aapt2 sortiert aufsteigend nach resId; der System-Parser verarbeitet
        // nur Attribute in korrekter Reihenfolge (Anhaengen ans Ende = EXCL=false).
        var insertPos = attrCount
        if (resIds != null) {
            for (i in 0 until attrCount) {
                val b = attrsBase + i * attrSize
                if (b + 20 > chunk.size) break
                val n = buf.getInt(b + 4)
                val resId = if (n in resIds.indices) resIds[n] else Int.MAX_VALUE
                if (resId < EXCLUDE_FROM_RECENTS_ID) insertPos = i + 1 else break
            }
        } else {
            // Ohne Map: direkt hinter android:name einfuegen (Position 1),
            // das ist die von aapt2 genutzte Lage fuer fruehe Attribute.
            insertPos = 1
        }
        // Bestehende Attribute ab insertPos um eins nach hinten schieben
        if (insertPos < attrCount) {
            System.arraycopy(result, attrsBase + insertPos * attrSize,
                result, attrsBase + (insertPos + 1) * attrSize,
                (attrCount - insertPos) * attrSize)
        }
        val base = attrsBase + insertPos * attrSize
        writeInt(result, base, nsIndex)
        writeInt(result, base + 4, nameIndex)
        writeInt(result, base + 8, NO_INDEX) // rawValue
        // Res_value (8 Bytes): size=u16, res0, dataType=0x12 (boolean),
        // data=0xFFFFFFFF = true (WICHTIG: wie aapt2, NICHT 1! Der System-
        // Parser auf OxygenOS uebernimmt data=1 nicht zuverlaessig — real
        // getroffen 08/2026: aapt2 zeigte das Attribut, dumpsys aber flags=0x0)
        result[base + 12] = 8
        result[base + 13] = 0
        result[base + 14] = 0
        result[base + 15] = TYPE_INT_BOOLEAN.toByte()
        result[base + 16] = -1
        result[base + 17] = -1
        result[base + 18] = -1
        result[base + 19] = -1
        return result
    }

    /** Index des activity-Start-Chunks mit MAIN/LAUNCHER-Intent-Filter. */
    private fun findLauncherActivityChunkIndex(chunks: List<Chunk>, pool: StringPool): Int? {
        var i = 0
        while (i < chunks.size) {
            val c = chunks[i]
            if (c.type == TYPE_START_ELEMENT && elementName(pool, c) == "activity") {
                if (activityHasLauncherIntentFilter(chunks, i, pool)) return i
            }
            i++
        }
        return null
    }

    /** Prueft, ob das activity-Element ab [startIdx] einen MAIN+LAUNCHER-Filter enthaelt. */
    private fun activityHasLauncherIntentFilter(chunks: List<Chunk>, startIdx: Int, pool: StringPool): Boolean {
        var depth = 0
        var i = startIdx
        var inIntentFilter = false
        var hasMain = false
        var hasLauncher = false
        while (i < chunks.size) {
            val c = chunks[i]
            when (c.type) {
                TYPE_START_ELEMENT -> {
                    val name = elementName(pool, c)
                    if (inIntentFilter) {
                        when (name) {
                            "action" -> if (permissionValue(pool, c) == ACTION_MAIN) hasMain = true
                            "category" -> if (permissionValue(pool, c) == CATEGORY_LAUNCHER) hasLauncher = true
                        }
                    } else if (name == "intent-filter") {
                        inIntentFilter = true
                    }
                    depth++
                }
                TYPE_END_ELEMENT -> {
                    depth--
                    if (inIntentFilter && depth == 1) {
                        if (hasMain && hasLauncher) return true
                        inIntentFilter = false
                        hasMain = false
                        hasLauncher = false
                    }
                    if (depth == 0) return false
                }
            }
            i++
        }
        return false
    }

    /** Liefert den voll-qualifizierten Klassennamen der Launcher-Activity (android:name). */
    fun launcherActivityClass(data: ByteArray): String? {
        val parsed = parse(data) ?: return null
        if (parsed.pool.isEmpty) return null
        val idx = findLauncherActivityChunkIndex(parsed.chunks, parsed.pool) ?: return null
        return attributeString(parsed.chunks[idx].bytes, parsed.pool, "name")
    }

    /**
     * Setzt das App-Label (android:label im application-Element) auf einen
     * direkten String-Wert. Der bestehende label-Wert kann eine Ressourcen-
     * Referenz sein (type=0x01 auf @string/app_name) — die wird auf
     * TYPE_STRING (0x03) mit dem neuen String umgestellt.
     * @return modifizierte Manifest-Bytes oder unverändert, wenn kein
     *         application-Element/label-Attribut existiert.
     */
    fun setApplicationLabel(data: ByteArray, label: String): ByteArray {
        if (data.size < 28 || label.isBlank()) return data
        return try {
            val parsed = parse(data) ?: return data
            val pool = parsed.pool
            if (pool.isEmpty) return data

            // 1. Neuen Label-String in den Pool hängen (falls nicht vorhanden)
            var poolBytes = pool.raw
            var labelIdx = pool.indexOf(label)
            if (labelIdx < 0) {
                val appended = appendPoolString(poolBytes, label) ?: return data
                poolBytes = appended.first
                labelIdx = appended.second
            }

            // 2. application-Element finden
            var appChunkIdx = -1
            for ((i, c) in parsed.chunks.withIndex()) {
                if (c.type == TYPE_START_ELEMENT && elementName(parsed.pool, c) == "application") {
                    appChunkIdx = i
                    break
                }
            }
            if (appChunkIdx < 0) return data

            // 3. label-Attribut im application-Chunk auf String umstellen
            val chunk = parsed.chunks[appChunkIdx].bytes
            val buf = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
            val attrStart = buf.getShort(24).toInt() and 0xFFFF
            val attrSize = buf.getShort(26).toInt() and 0xFFFF
            val attrCount = buf.getShort(28).toInt() and 0xFFFF
            val attrsBase = 16 + attrStart
            var newChunk: ByteArray? = null
            for (i in 0 until attrCount) {
                val base = attrsBase + i * attrSize
                if (base + 20 > chunk.size) break
                val nsRef = buf.getInt(base)
                val nameRef = buf.getInt(base + 4)
                if (pool.get(nameRef) != "label") continue
                val ns = pool.get(nsRef)
                if (nsRef != NO_INDEX && ns != ANDROID_NS) continue
                val copy = chunk.copyOf()
                // rawValue = String-Index des neuen Labels
                writeInt(copy, base + 8, labelIdx)
                // Res_value: size=8, res0=0, dataType=0x03 (TYPE_STRING), data=String-Index
                copy[base + 12] = 8
                copy[base + 13] = 0
                copy[base + 14] = 0
                copy[base + 15] = 0x03
                writeInt(copy, base + 16, labelIdx)
                newChunk = copy
                break
            }
            if (newChunk == null) return data

            // 4. Chunks zusammensetzen: Pool + application-Chunk ersetzen
            val out = java.io.ByteArrayOutputStream()
            for ((i, c) in parsed.chunks.withIndex()) {
                when {
                    c.type == TYPE_STRING_POOL -> out.write(poolBytes)
                    i == appChunkIdx -> out.write(newChunk)
                    else -> out.write(c.bytes)
                }
            }
            val newSize = 8 + out.size()
            val result = ByteArray(newSize)
            writeU16(result, 0, 0x0003)
            writeU16(result, 2, 0x0008)
            writeInt(result, 4, newSize)
            System.arraycopy(out.toByteArray(), 0, result, 8, out.size())
            result
        } catch (_: Throwable) {
            data
        }
    }

    /**
     * App-Cloning: Tauscht Paket-Strings im Binary-XML (package-Attribut + alle
     * Komponenten-/Autority-Namen, die mit oldPkg beginnen). Da newPkg länger ist
     * als oldPkg (Suffix .stealth), wird der StringPool KOMPLETT neu serialisiert.
     * Element-Chunks bleiben unverändert, weil sie Strings NUR per Index
     * referenzieren und alle Indizes erhalten bleiben.
     */
    fun renamePackage(data: ByteArray, oldPkg: String, newPkg: String): ByteArray {
        if (data.size < 28 || oldPkg == newPkg) return data
        return try {
            val parsed = parse(data) ?: return data
            val pool = parsed.pool
            if (pool.isEmpty) return data
            // Welche Indizes müssen ersetzt werden? Nur exakte Paket-Präfixe,
            // keine Teilstrings ("de.baumann.browserx" bleibt unangetastet).
            // Idempotenz: Strings, die bereits mit newPkg beginnen, NICHT anfassen.
            val replacements = HashMap<Int, String>()
            for (i in 0 until pool.count) {
                val s = pool.get(i)
                if (s.isEmpty()) continue
                if (s == oldPkg) {
                    replacements[i] = newPkg
                } else if (s.startsWith(newPkg) || s.startsWith(newPkg.replace('.', '/') + "/")) {
                    // bereits umbenannt — nichts tun
                } else if (s.startsWith("$oldPkg.")) {
                    replacements[i] = s.replaceFirst(oldPkg, newPkg)
                }
            }
            if (replacements.isEmpty()) return data

            // Neuen Pool bauen: Header (28 Bytes) + Offset-Array + Strings + Styles
            val headerSize = 28
            val isUtf8 = (pool.poolFlags and 0x100) != 0
            val buf = java.io.ByteArrayOutputStream()
            val offsets = IntArray(pool.count)
            for (i in 0 until pool.count) {
                offsets[i] = buf.size()
                val s = replacements[i] ?: pool.get(i)
                writePoolString(buf, s, isUtf8)
            }
            val stringBytes = buf.toByteArray()
            // Styles-Bereich aus dem alten Pool 1:1 übernehmen (enthält nur
            // Pool-Indizes — die bleiben gültig, weil Indizes unverändert sind).
            val stylesStart = pool.stylesStart
            val oldPoolChunkSize = ByteBuffer.wrap(pool.raw).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
            val stylesBytes = if (pool.styleCountVal > 0 && stylesStart in 0 until oldPoolChunkSize) {
                pool.raw.copyOfRange(stylesStart, oldPoolChunkSize)
            } else ByteArray(0)

            val newPoolSize = headerSize + pool.count * 4 + stringBytes.size + stylesBytes.size
            // Android-Binary-XML: Alle Chunks müssen auf 4-Byte-Grenzen liegen —
            // der StringPool wird mit NUL-Bytes aufgefüllt, wenn seine Größe kein
            // Vielfaches von 4 ist. (Real getroffen 08/2026: "XML size ... is not
            // on an integer boundary" im natives Parser ApkAssets/aapt2.)
            val paddedPoolSize = (newPoolSize + 3) and 0x7FFFFFFC
            val poolOut = ByteBuffer.allocate(paddedPoolSize).order(ByteOrder.LITTLE_ENDIAN)
            poolOut.putShort(TYPE_STRING_POOL.toShort())
            poolOut.putShort(headerSize.toShort())
            poolOut.putInt(paddedPoolSize)
            poolOut.putInt(pool.count)
            poolOut.putInt(pool.styleCountVal)
            poolOut.putInt(pool.poolFlags)
            poolOut.putInt(headerSize + pool.count * 4) // stringsStart
            val newStylesStart = if (stylesBytes.isEmpty()) 0 else headerSize + pool.count * 4 + stringBytes.size
            poolOut.putInt(newStylesStart)
            for (o in offsets) poolOut.putInt(o)
            poolOut.put(stringBytes)
            poolOut.put(stylesBytes)
            // Rest (Padding bis 4-Byte-Grenze) bleibt 0x00
            val newPool = poolOut.array()

            // Datei-Aufbau: [8 Bytes XML-Header (0x0003+headerSize+fileSize)] [StringPool] [weitere Chunks]
            // parse() beginnt bei Offset 8, daher ist der Pool dort der erste Chunk.
            // WICHTIG: Der Pool beginnt bei 8, nicht bei 0 — der Tail startet also
            // bei 8 + oldPoolChunkSize (realer Bug 08/2026: "Malformed chunk: header
            // too long" im ApkSigner, weil 8 Bytes verloren gingen).
            val xmlHeader = data.copyOfRange(0, 8)
            val tail = data.copyOfRange(8 + oldPoolChunkSize, data.size)
            val result = ByteArray(xmlHeader.size + newPool.size + tail.size)
            System.arraycopy(xmlHeader, 0, result, 0, xmlHeader.size)
            System.arraycopy(newPool, 0, result, xmlHeader.size, newPool.size)
            System.arraycopy(tail, 0, result, xmlHeader.size + newPool.size, tail.size)

            // XML-Datei-Header (0x0003): fileSize bei Offset 4 aktualisieren
            ByteBuffer.wrap(result, 4, 4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(result.size)
            result
        } catch (_: Throwable) {
            data
        }
    }

    /** Schreibt einen String gemäß Pool-Flags (UTF-8 oder UTF-16, NUL-terminiert). */
    private fun writePoolString(out: java.io.ByteArrayOutputStream, s: String, utf8: Boolean) {
        if (utf8) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            val charLen = s.length
            val byteLen = bytes.size
            if (charLen >= 0x80) { out.write(0x80 or (charLen shr 8)); out.write(charLen and 0xFF) } else out.write(charLen)
            if (byteLen >= 0x80) { out.write(0x80 or (byteLen shr 8)); out.write(byteLen and 0xFF) } else out.write(byteLen)
            out.write(bytes); out.write(0)
        } else {
            // UTF-16: Länge als uint16 LITTLE-ENDIAN (low byte zuerst!)
            // Bei len >= 0x8000: 4 Bytes — erstes uint16 = 0x8000 | (len>>16) (LE),
            // zweites uint16 = len & 0xFFFF (LE). Passt zu readUtf16.
            val chars = s.toCharArray()
            val len = chars.size
            if (len >= 0x8000) {
                val high = 0x8000 or ((len shr 16) and 0x7FFF)
                out.write(high and 0xFF); out.write((high shr 8) and 0xFF)
                out.write(len and 0xFF); out.write((len shr 8) and 0xFF)
            } else {
                out.write(len and 0xFF); out.write((len shr 8) and 0xFF)
            }
            for (c in chars) { out.write(c.code and 0xFF); out.write((c.code shr 8) and 0xFF) }
            out.write(0); out.write(0)
        }
    }

    /** Liest den String-Wert eines Attributes des Start-Elements (z.B. android:name). */
    private fun attributeString(chunk: ByteArray, pool: StringPool, attrName: String): String? {
        val buf = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
        val attrStart = buf.getShort(24).toInt() and 0xFFFF
        val attrSize = buf.getShort(26).toInt() and 0xFFFF
        val attrCount = buf.getShort(28).toInt() and 0xFFFF
        val attrsBase = 16 + attrStart
        for (i in 0 until attrCount) {
            val base = attrsBase + i * attrSize
            if (base + 20 > chunk.size) break
            val nameRef = buf.getInt(base + 4)
            if (pool.get(nameRef) != attrName) continue
            // rawValue = StringPool-Index des Werts
            val raw = buf.getInt(base + 8)
            if (raw == NO_INDEX) return null
            return pool.get(raw)
        }
        return null
    }

    // ------------------------------------------------------------------
    // Intern
    // ------------------------------------------------------------------

    private class StringPool(private val data: ByteArray) {
        val isEmpty: Boolean get() = data.size < 28
        private val strings: List<String> by lazy { parse() }

        internal val raw: ByteArray get() = data
        internal val count: Int get() = if (data.size >= 12) ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(8) else 0
        internal val poolFlags: Int get() = if (data.size >= 20) ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(16) else 0
        internal val stringsStart: Int get() = if (data.size >= 24) ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(20) else 0
        internal val styleCountVal: Int get() = if (data.size >= 16) ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(12) else 0
        internal val stylesStart: Int get() = if (data.size >= 28) ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(24) else 0

        internal fun indexOf(s: String): Int {
            val list = strings
            for (i in list.indices) if (list[i] == s) return i
            return -1
        }

        private fun parse(): List<String> {
            if (data.size < 28) return emptyList()
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val stringCount = buf.getInt(8)
            val flags = buf.getInt(16)
            val stringsStart = buf.getInt(20)
            val utf8 = flags and 0x100 != 0
            val list = ArrayList<String>(stringCount)
            for (i in 0 until stringCount) {
                val off = buf.getInt(28 + i * 4)
                // stringsStart ist relativ zum Chunk-Anfang (Offset 0), NICHT zu Offset 8
                val abs = stringsStart + off
                if (abs < 0 || abs >= data.size) {
                    list.add("")
                    continue
                }
                val s = try {
                    if (utf8) readUtf8(data, abs) else readUtf16(data, abs)
                } catch (e: Exception) {
                    ""
                }
                list.add(s)
            }
            return list
        }

        fun get(index: Int): String =
            if (index != NO_INDEX && index in strings.indices) strings[index] else ""

        private fun readUtf16(data: ByteArray, pos: Int): String {
            var p = pos
            var len = (data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8)
            p += 2
            if (len and 0x8000 != 0) {
                len = ((len and 0x7FFF) shl 16) or
                    ((data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8))
                p += 2
            }
            val sb = StringBuilder(len)
            for (i in 0 until len) {
                val c = (data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8)
                sb.append(c.toChar())
                p += 2
            }
            return sb.toString()
        }

        private fun readUtf8(data: ByteArray, pos: Int): String {
            var p = pos
            var charLen = data[p].toInt() and 0xFF
            p++
            if (charLen and 0x80 != 0) p++ // erweiterte Zeichenlänge überspringen
            var byteLen = data[p].toInt() and 0xFF
            p++
            if (byteLen and 0x80 != 0) {
                byteLen = ((byteLen and 0x7F) shl 8) or (data[p].toInt() and 0xFF)
                p++
            }
            if (p + byteLen > data.size) return ""
            return String(data, p, byteLen, Charsets.UTF_8)
        }
    }

    private class Chunk(val type: Int, val bytes: ByteArray)

    private class Parsed(val pool: StringPool, val chunks: List<Chunk>)

    private fun parse(data: ByteArray): Parsed? {
        if (data.size < 8) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val fileSize = buf.getInt(4)
        if (fileSize < 8 || fileSize > data.size) return null
        var pool: StringPool? = null
        val chunks = ArrayList<Chunk>()
        var offset = 8
        while (offset + 8 <= fileSize) {
            val type = buf.getShort(offset).toInt() and 0xFFFF
            val csize = buf.getInt(offset + 4)
            if (csize < 8 || offset + csize > fileSize) break
            val bytes = data.copyOfRange(offset, offset + csize)
            if (type == TYPE_STRING_POOL && pool == null) pool = StringPool(bytes)
            chunks.add(Chunk(type, bytes))
            offset += csize
        }
        return Parsed(pool ?: StringPool(ByteArray(0)), chunks)
    }

    /** Elementname eines Start-Tags. Layout: lineNumber@8, comment@12, ns@16, name@20. */
    private fun elementName(pool: StringPool, c: Chunk): String {
        if (c.bytes.size < 24) return ""
        val ref = ByteBuffer.wrap(c.bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(20)
        return pool.get(ref)
    }

    /** Wert des android:name-Attributs eines <uses-permission>-Elements. */
    private fun permissionValue(pool: StringPool, c: Chunk): String? {
        val buf = ByteBuffer.wrap(c.bytes).order(ByteOrder.LITTLE_ENDIAN)
        val attrStart = buf.getShort(24).toInt() and 0xFFFF
        val attrSize = buf.getShort(26).toInt() and 0xFFFF
        val attrCount = buf.getShort(28).toInt() and 0xFFFF
        if (attrSize < 20) return null
        val attrsBase = 16 + attrStart
        for (i in 0 until attrCount) {
            val base = attrsBase + i * attrSize
            if (base + 20 > c.bytes.size) break
            val nsRef = buf.getInt(base)
            val nameRef = buf.getInt(base + 4)
            val rawRef = buf.getInt(base + 8)
            if (pool.get(nameRef) != "name") continue
            val ns = pool.get(nsRef)
            if (nsRef != NO_INDEX && ns != ANDROID_NS) continue
            if (rawRef == NO_INDEX) continue
            val v = pool.get(rawRef)
            return if (v.isEmpty()) null else v
        }
        return null
    }

    /** Wert eines benannten Attributs (z.B. "package" am Manifest-Root). */
    private fun attributeValue(pool: StringPool, c: Chunk, attrName: String): String? {
        if (c.bytes.size < 24) return null
        val buf = ByteBuffer.wrap(c.bytes).order(ByteOrder.LITTLE_ENDIAN)
        val attrStart = buf.getShort(24).toInt() and 0xFFFF
        val attrSize = buf.getShort(26).toInt() and 0xFFFF
        val attrCount = buf.getShort(28).toInt() and 0xFFFF
        if (attrSize < 20) return null
        val attrsBase = 16 + attrStart
        for (i in 0 until attrCount) {
            val base = attrsBase + i * attrSize
            if (base + 20 > c.bytes.size) break
            val nameRef = buf.getInt(base + 4)
            val rawRef = buf.getInt(base + 8)
            if (pool.get(nameRef) != attrName) continue
            if (rawRef == NO_INDEX) continue
            return pool.get(rawRef).ifEmpty { null }
        }
        return null
    }

    private fun writeU16(arr: ByteArray, off: Int, v: Int) {
        arr[off] = (v and 0xFF).toByte()
        arr[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun writeInt(arr: ByteArray, off: Int, v: Int) {
        arr[off] = (v and 0xFF).toByte()
        arr[off + 1] = ((v shr 8) and 0xFF).toByte()
        arr[off + 2] = ((v shr 16) and 0xFF).toByte()
        arr[off + 3] = ((v shr 24) and 0xFF).toByte()
    }
}
