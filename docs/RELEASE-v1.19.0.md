# App Ops Shizuku 1.19.0

Grundlage ist PR #1 (`ccr-8ef79b0f-zw466o`, Commit `7faaf18`): Berechtigungen beim Patchen entfernen.
Der Branch selbst enthielt **keine** Versionserhöhung — sie ist in diesem Release nachgeholt
(1.18.1/33 → 1.19.0/34), sonst würde der Update-Kanal die Fassung nicht als neu erkennen.

## 1.19.0 (34)

**Berechtigungen entfernen (Beta)** — neuer Abschnitt im Reiter Tarnung, direkt unter
„Tracker abschalten". Sechs Kästchen entfernen `<uses-permission>`-Einträge (auch
`uses-permission-sdk-23`) aus dem Manifest der gepatchten App:

| Kästchen | Entfernte Berechtigungen |
|---|---|
| Kein Internet (Offline-Klon) | `INTERNET` |
| Keine Werbe-ID | `AD_ID`, `ACCESS_ADSERVICES_*` |
| Installierte Apps nicht sichtbar | `QUERY_ALL_PACKAGES` |
| Kein Standort | `ACCESS_FINE/COARSE/BACKGROUND_LOCATION` |
| Kein Telefonstatus / keine Rufnummer | `READ_PHONE_STATE`, `READ_PHONE_NUMBERS` |
| Keine Kontenliste | `GET_ACCOUNTS` |

Anders als `pm revoke` ist das eine Eigenschaft der installierten APK: übersteht Neustarts, braucht kein
laufendes Shizuku, die App kann nicht erneut fragen. Vor der Installation wird das Manifest der
**signierten** APK mit dem Original verglichen; steht eine gewählte Berechtigung noch drin, wird nichts
installiert.

Neue Logik: `appops/PermissionStrip.kt` (reine JVM-Logik, nutzt das bisher ungenutzte
`BinaryXml.removePermissions`), verdrahtet über `StealthFragment`, `StealthPatchService`,
`StealthStealer`. 19 neue Texte in allen 9 Sprachen (333 je Sprache).

## Prüfung

- `JAVA_HOME=/opt/jdk-21.0.6+7 ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug`
  → **BUILD SUCCESSFUL in 45 s**
- Tests aus den XML-Resultaten summiert: **249 Tests, 0 Failures, 0 Errors, 1 übersprungen**
  (27 Testdateien; neu: `PermissionStripTest` mit **11** Tests)
- APK aus dem Arbeitsbaum: `de.minipc.appopsshizuku`, versionCode **34**, versionName **1.19.0**,
  targetSdk 35, 34.982.924 Bytes
- Signatur: Debug-Zertifikat SHA-256 `652394f7…7226e` — identisch mit 1.18.1, die APK ist als Update
  installierbar
- Veröffentlichung über `scripts/release.sh` (Build, Tests, Signatur, Push, Release, Asset-Upload,
  Download-Gegenprobe); die angehängte APK ist der Build aus dem getaggten Commit

## Offene Punkte

- **Nicht am Gerät getestet** (PR und dieses Release): ob ein Offline-Klon einer kleinen App startet und
  wirklich kein Netz bekommt; ob Spooftest mit „Keine Werbe-ID" eine Nullen-ID zeigt; die
  Manifest-Prüfung ist nur über Unit-Tests belegt (aapt2 an synthetischen und echten Manifesten).
- Die neue UI-Sektion ist nicht als Screenshot abgenommen (kein Gerät in dieser Runde).
- **Frischer Checkout kompiliert nicht:** `app/src/main/res/raw/calcyou.apk` (21,6 MB) wurde in
  `685d052` bewusst aus dem Baum entfernt und fällt unter `*.apk` in `.gitignore`. Lokal liegt die Datei
  weiter im Arbeitsbaum, deshalb baut dieser Rechner; ein Klon braucht sie zusätzlich
  (`R.raw.calcyou` in `StealthPatchService`).
