### English

**App Ops Shizuku 1.20.4** — the *Activity* screen now recognises sensor apps on real Android builds.

**Fixed:** on a OnePlus running Android 17 the diagnostics found **0 sensor apps**. The parser looked for `package=`, which that `dumpsys sensorservice` output does not contain. According to AOSP (`SensorEventConnection.cpp`, `SensorRegistrationInfo.h`) the lines are:

- active connection: `com.pkg | WakeLockRefCount 0 | uid 10123 | …`
- earlier registration: `… result=OK (sensor, package): (Accelerometer, com.pkg)`

Both formats are now parsed; the old `package=` / `pkg=` marker stays as a fallback for older output. A new unit test covers the AOSP formats.

**Honest limits**

- The fix is verified against the AOSP source and unit tests, **not** on a device with the new output — the report that triggered it came from a single Android 17 device.
- The activity scan stays heuristic: an app that queried a sensor yesterday but no longer appears in the dump is not listed.

**Tests:** 345 unit tests, 0 failures, 1 skipped (44 test classes).

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest — this repository is **private**; for updates through [Obtainium](https://github.com/ImranR98/Obtainium) you need a GitHub token with read access.

### Deutsch

**App Ops Shizuku 1.20.4** — die Seite *Aktivität* erkennt Sensor-Apps jetzt auch auf echten Android-Builds.

**Behoben:** Auf einem OnePlus mit Android 17 meldete die Diagnose **0 Sensor-Apps**. Der Parser suchte nach `package=`, das steht in dieser `dumpsys sensorservice`-Ausgabe nicht drin. Laut AOSP (`SensorEventConnection.cpp`, `SensorRegistrationInfo.h`) lauten die Zeilen:

- aktive Verbindung: `com.pkg | WakeLockRefCount 0 | uid 10123 | …`
- frühere Registrierung: `… result=OK (sensor, package): (Accelerometer, com.pkg)`

Beide Formate werden jetzt erkannt; der alte Marker `package=` / `pkg=` bleibt als Rückfall für ältere Ausgaben. Ein neuer Unit-Test deckt die AOSP-Formate ab.

**Ehrliche Grenzen**

- Der Fix ist gegen den AOSP-Quelltext und per Unit-Test belegt, **nicht** auf einem Gerät mit der neuen Ausgabe — der Befund kam von genau einem Android-17-Gerät.
- Die Aktivitäts-Auswertung bleibt heuristisch: Eine App, die gestern einen Sensor abgefragt hat, im Dump aber nicht mehr auftaucht, wird nicht gelistet.

**Tests:** 345 Unit-Tests, 0 Fehler, 1 übersprungen (44 Testklassen).

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest — dieses Repository ist **privat**; für Updates über [Obtainium](https://github.com/ImranR98/Obtainium) ist ein GitHub-Token mit Lesezugriff nötig.

<sub>Paket `de.minipc.appopsshizuku` · versionCode 44 · debug-signiert (Zertifikat SHA-256 `652394f7…7226e`, updatefähig über 1.20.3) · targetSdk 35 · Lizenz GPL-3.0</sub>
