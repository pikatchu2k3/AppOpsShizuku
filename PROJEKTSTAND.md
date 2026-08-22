---
Status: funktionsfähig (v1.12.5 — CLIPBOARD-CRASH GEFIXT + deny-WARNUNG, E2E-verifiziert Emulator)
Datum: 2026-08-22
Projekt: AppOpsShizuku — Privacy-AppOps/Shizuku-Verwaltung für beliebige Apps (on-device, kein Root)
Pfade:
  Quellcode: ~/AppOpsShizuku/app/src/main/java/de/minipc/appopsshizuku/
  Test: ~/AppOpsShizuku/app/src/test/java/de/minipc/appopsshizuku/AppOpsControllerTest.kt
  Build: ~/AppOpsShizuku/app/build/outputs/apk/debug/app-debug.apk
Abhängigkeiten: dev.rikka.shizuku:api:13.1.5, dev.rikka.shizuku:provider:13.1.5, AGP 8.3.2, Kotlin 1.9.23
---

# TL;DR

Separate App neben AppClonerLite: verwaltet AppOps/Permissions beliebiger installierter Apps
über Shizuku (adb-Rechte, KEIN Root, KEIN Xposed). Feature 1 = Privacy-Profil: Kamera,
Mikrofon, Kontakte, Kalender, Anrufliste, Konten, Zwischenablage, Sensoren blockieren/freigeben.

# v1.12.5 (08/2026) — deny-Warnung bei Zwischenablage (User-Frage: „Öffnet die App noch?")

**Live-Test (Emulator):** READ/WRITE_CLIPBOARD auf `deny` → **FOSS Browser crasht beim Start**
(`SecurityException: de.baumann.browser … not allowed to perform READ_CLIPBOARD`). Mit
`ignore` → App öffnet normal, Clipboard ist trotzdem blockiert (stille Blockade, App bekommt
leere Daten). Bestätigt das Skill-Wissen: deny = harte Ablehnung (Crash-Gefahr), ignore =
empfohlener Modus für Clipboard.

**Fix:** Warn-Toast (`op_deny_warning`), wenn bei einer reinen AppOp (permissionName == null,
also READ/WRITE_CLIPBOARD) der Modus `deny` gewählt wird: „Achtung: deny kann die App beim
Start crashen lassen (SecurityException). Für die Zwischenablage ist ignore die bessere
Wahl (stille Blockade)." E2E: deny gesetzt → 2 Toasts (op_set_ok + Warnung), kein Crash.

# v1.12.4 (08/2026) — Clipboard-Crash behoben (User-Report: App sprang zum Hauptmenü)

**User-Report (real):** „Wenn ich … Zwischenablage lesen/Zwischenablage schreiben … zu deny
verändern will, springt App Ops Shizuku zurück zum Hauptmenü" (FOSS Browser).

**Zwei Root Causes (beide live im Emulator reproduziert und gefixt):**

1. **`PrivacyProfileStore.setOpMode()` crashte mit `JSONException: End of input`**
   (FATAL EXCEPTION: main → App stürzt ab → „zurück zum Hauptmenü"). Ursache: Beim ersten
   Setzen einer Op einer App (kein gespeichertes Profil) lieferte `getString(pkg, null)
   .orEmpty()` einen LEEREN String → `JSONObject("")` → JSONException auf dem Main-Thread
   (handler.post in onModeSelected). Fix: leeres/ungültiges JSON → neues `JSONObject()`;
   zusätzlich defensiv `runCatching` um setOpMode in onModeSelected (UI-Thread darf nie
   crashen).
2. **`AppOpsController.getMode()` erkannte reine AppOps nicht:** `cmd appops get <pkg>
   READ_CLIPBOARD` liefert NUR die Paket-Ebene (`READ_CLIPBOARD: deny`) — OHNE
   `Uid mode:`-Zeile (die gibt es nur bei Permission-Ops). Der Parser gab deshalb
   fälschlich `default` zurück → UI zeigte nach jedem Reload den falschen Zustand (deny
   „sprang zurück" auf default). Fix: Paket-Ebene parsen (nur wenn der Präfix zum
   Op-Namen passt, damit „Default mode: allow" nicht verfälscht).

**E2E-verifiziert (Emulator API 35, frische App-Daten via pm clear — exakt der
User-Zustand):** Alle 13 Privacy-Ops einzeln auf deny/allow/default geändert — KEIN Crash;
„Alle auf Standard" ohne Crash; Neustart zeigt korrekte Zustände. Unit-Tests erweitert
(5 neue getMode-Parser-Fälle, AppOpsControllerTest 18/18 grün). APK v1.12.4, MD5 siehe
Build-Ausgabe. Hinweis: Bei FOSS Browser sind viele Permissions (CAMERA, READ_CONTACTS, …)
nicht deklariert → `pm grant`/`pm revoke` ist ein No-op (Uid mode bleibt ignore) — die UI
zeigt nach Reload den echten Zustand; kein Crash, bekanntes Verhalten.

# v1.12.3 (08/2026) — „Im Verlauf verstecken" default AUS (User-Korrektur)

**User-Korrektur (real):** „Es sollte auch nicht default an sein eigentlich" — der Switch
„Im Verlauf (letzte Apps) verstecken" war im Layout (`activity_components.xml`) fälschlich
`android:checked="true"`. Beim Klonen ohne Verstecken-Absicht wurde so trotzdem
excludeFromRecents gesetzt (User hatte es manuell ausgemacht).

**Fix:** `android:checked="false"` im Layout; einziger Default-AN-Switch bleibt der
Clone-Switch (expliziter User-Wunsch 08/2026). E2E: frisch installierte v1.12.3 zeigt
`stealthExcludeFromRecentsSwitch checked=false`, `stealthCloneSwitch checked=true`.
Tests grün; APK 13 MB, MD5 `6b26af95a98d5e697bed5c717fb95bb7`.

**GELÖST (User-bestätigt 08/2026):** Google-Maps-Klon (v1.12.2, Clone + 3× Spoof,
Verstecken AN) installierte sich, erschien aber nicht im Launcher (unter
Einstellungen/Apps sichtbar). Ursache: der „Im Verlauf verstecken"-Switch
(excludeFromRecents) — auf dem OnePlus/OxygenOS verbannt er den Klon aus dem LAUNCHER,
obwohl er nur die Recents-Liste betreffen sollte. Mit v1.12.3 (Switch default AUS) war
der Klon sofort im Launcher (User: „Es hat geklappt."). Lehre: Stealth-Attribute als
potenziell launcher-filternd behandeln; resolve-activity auf dem Emulator reicht als
Beweis nicht (dort war sie ok, auf dem OnePlus trotzdem kein Icon).

# v1.12.2 (08/2026) — Split-APK-Support: Google Maps klonen GEFIXT (E2E-BEWIESEN)

**User-Fehler (real):** `INSTALL_FAILED_MISSING_SPLIT: Missing split for mapsy.stealth` beim
Google-Maps-Klon (Maps ist ein App-Bundle: base + config.*-Splits; der Extraktor holte nur
die base).

**Umsetzung:**
1. `extractApk` extrahiert ALLE `pm path`-Pfade (base → `original.apk`, Splits →
   `original_split_<name>.apk`), chunked mit Gesamt-Prozenten; löscht veraltete Originale
   vorher.
2. `buildStealthApkWithSplits`: patcht/signiert nur die base; jeder Split wird repackt +
   mit DERSELBEN Signatur neu signiert (ApkSigner cached den KeyPair). Beim Cloning wird
   im Split-Manifest zusätzlich der Paketname umgeschrieben (Installer matcht Splits über
   Paketname + Signatur).
3. `installApk(List)`: ⚠️ `pm install-multiple` existiert auf modernen Androids NICHT mehr
   (live: „Unknown command: install-multiple") → PackageInstaller-Session:
   `pm install-create -r -S <total>` → Session-ID parsen → pro Datei
   `pm install-write -S <size> <session> <splitName> <pfad>` (splitName = „base.apk" bzw.
   Original-Split-Name) → `pm install-commit <session>`; Signaturkonflikt-Retry ohne -r.
4. UI: Split-Warnung im Bestätigungsdialog wenn `applicationInfo.splitNames` nicht leer.
5. Wiederverwendungs-Falle gefixt: extrahierte Originale nur wiederverwenden, wenn Anzahl ==
   `pm path`-Zeilen (alter workDir ohne Splits → Klon kam sonst ohne Splits an).

**E2E-Beweis (Emulator):** spooftest mit bundletool-Splits (base-master + config.en +
config.xxhdpi) installiert → Klon `splittest.stealth` zeigt alle 3 Pfade in `pm path`,
startet; Split-Warnung im Dialog sichtbar; Restore stellt Original mit Splits wieder her.
Tests grün; APK 13 MB, MD5 `de6e247b6bcdcb89b45b84f4198def07`.

# v1.12.1 (08/2026) — Notification-Fix + Prozent im Kreis (E2E-BEWIESEN)

**User-Wünsche (real):** (1) „Benachrichtigungen kann ich nicht erlauben bei meinem Telefon" —
(2) „eine Art Prozent Anzeige noch in dem Kreis, sodass ich weiß wie weit der Prozess ist".

**Fixes/Features:**
1. **`POST_NOTIFICATIONS` im Manifest + Runtime-Request** (`REQ_NOTIF=5001`, SDK≥33) — ohne
   Manifest-Eintrag existiert der Einstellungs-Schalter auf Android 13+ gar nicht. Request beim
   Patch-Start; Patch läuft auch ohne Grant weiter (Fortschritt im Dialog sichtbar). E2E:
   `pm revoke` → System-Dialog „Allow App Ops Shizuku to send you notifications?" erschien.
2. **`overallPercent(phase, pct)`** — gewichtete Gesamt-Anzeige 0–100 % (EXTRACT 0–15,
   DEX_PATCH 15–70, REPACK 70–80, SIGN 80–88, TRANSFER 88–96, INSTALL 96–100, RESTORE pct);
   gleicher Wert in Dialog-Kreismitte + Notification.
3. **BUG-FIX `progressDialogView`**: `dialog.findViewById(android.R.id.custom)` liefert bei
   `MaterialAlertDialogBuilder` NULL (logcat: `updateProgressDialog: custom-View NULL`) →
   View beim Inflaten in Feld merken, darüber updaten; `dismissProgressDialog` cleart
   `FLAG_KEEP_SCREEN_ON`.
4. **Erfolg/Fehler als MaterialAlertDialog statt Toast** (Skill-Regel; Toast zu kurz zum Lesen).

**E2E-Beweis (Emulator, v1.12.1, Clone „prozt" + alle 3 Spoof-Switches):**
- Watch-Loop (uiautomator, 1s) fing live: `pct='33 %'` `phase='Patch App-Code (DEX)…'` über
  14 Iterationen → Screenshot mid_4.png zeigt „Stealth-Modus wird angewendet / 33 %" im Kreis.
- Danach `success='✓ Erfolg'` → Erfolgsdialog; Klon `prozt.stealth` installiert.
- Build: `:app:testDebugUnitTest` grün, `assembleDebug` OK. APK 13 MB,
  MD5 `d907890d937785ef13090f2e96f8251b`.

**OFFEN (User: „Fixe später"):** Google-Maps-Klon → `INSTALL_FAILED_MISSING_SPLIT: Missing
split for <klon>.stealth` — Maps ist App-Bundle (base + config.*-Splits); Extraktor holt nur
base. Fix-Plan im Skill `android-apk-modding`: alle Pfade aus `pm path` extrahieren, nur base
patchen, Splits mit neuer Signatur signieren, `pm install-multiple` verwenden.

# v1.12.0 (08/2026) — Patch läuft jetzt im Foreground-Service + Fortschritts-UI + CPU

**User-Problem (real):** Google-Maps-Patch (Clone + 3× Spoof) dauerte 9+ Minuten und hing,
sobald der User zu einer anderen App wechselte. CPU taktete auch im Vordergrund kaum hoch.

**Ursachen (alle live belegt):**
1. Patch lief im Activity-Thread → OxygenOS-Freezer + Hintergrund-Cpuset beim App-Wechsel.
2. Base64-Transfer der 150MB-APK als EIN 200MB-String → 192MB-App-Heap-OOM-Risiko + 180s-Timeout-Risiko.
3. installApk nutzte readBytes() der ganzen signierten APK (RAM-Killer bei 150MB).
4. smali-Assembly mit jobs=1 (OOM-Schutz) → nur 1 Kern; CPU-Governor taktet bei
   Single-Thread-I/O-Last kaum hoch; kein CPU-Boost-Hint an den PowerHAL.

**Lösung (alles E2E-verifiziert auf Emulator, 08/2026):**
1. **`StealthPatchService` (Foreground-Service, dataSync)** — Patch + Restore laufen im
   Service; `startForegroundService` aus der Activity; Fortschritt in Notification
   (setProgress) + an die Activity via statischem Companion-Kanal (gleicher Prozess:
   `currentPhase/currentPercent/lastResult/progressListener`). Notification tappbar
   (öffnet ComponentsActivity). E2E: Home → Patch lief weiter, Klon installiert,
   Erfolgsdialog nach Rückkehr.
2. **Progress-Dialog (Material, setCancelable(false))** — CircularProgressIndicator
   (determinate mit %, indeterminate bei unbekannter Dauer) + Phasentext + Prozent.
   Phasen: EXTRACT/DEX_PATCH/REPACK/SIGN/TRANSFER/INSTALL/RESTORE (Enum `StealthPhase`,
   `progressListener` in StealthStealer). Nach App-Wechsel + Rückkehr: onResume zeigt
   Dialog wieder (aktueller Stand) bzw. Ergebnis-Dialog.
3. **Erfolgs-/Fehlerdialog statt Toast** („✓ Erfolg"/„Fehler" + OK) — Toast war zu kurz.
4. **Chunked Transfers** — extractApk: 4MB-Chunks `dd skip=N count=1 | base64` (echte %,
   kein OOM, kein Timeout); installApk: 8MB-Stdin-Chunks `cat >>` (echte %). Kleine APKs
   ohne stat → Fallback Einzel-Call.
5. **CPU-Optimierung (Qwen-Analyse verifiziert eingebaut):**
   - `Process.setThreadPriority(THREAD_PRIORITY_FOREGROUND)` im Service-Worker.
   - smali/baksmali **jobs adaptiv** (`Runtime.maxMemory()`: ≤220MB→1, ≤384MB→1, ≤512MB→2,
     sonst 2) + OutOfMemoryError-Fallback auf jobs=1 (doppeltes Netz).
   - **WakeLock** (PARTIAL, 2h-Timeout) im Service gegen CPU-Suspend.
   - **ADPF PerformanceHintSession** (API 31+, Reflection, getSystemService("performance"))
     — root-freier CPU-Boost-Hint, reportActualWorkDuration alle 100ms.
   - **AppOps/Standby als shell-UID** (auf die EIGENE App, reversibel): RUN_IN_BACKGROUND,
     RUN_ANY_IN_BACKGROUND, START_FOREGROUND_SERVICES_FROM_BACKGROUND, WAKE_LOCK allow +
     `cmd deviceidle whitelist +pkg` + `set-standby-bucket active` + `set-inactive false`.
   - **FLAG_KEEP_SCREEN_ON** solange der Progress-Dialog sichtbar ist (App bleibt
     Vordergrund/top-app → volle CPU — auf OxygenOS der zuverlässigste Boost).
   ⚠️ Realistisch (Qwen): Ohne Root KEINE garantierte volle Frequenz im Hintergrund;
   OxygenOS kann trotz FGS drosseln. Zuverlässigste Option bleibt: App im Vordergrund
   lassen (Screen bleibt durch KEEP_SCREEN_ON an). Governor-Sysfs ist shell-UID NICHT
   beschreibbar (root-only, SELinux) — nicht versuchen.
6. **Erweiterte AppOps-Kommandos** im Service-Start (siehe oben) — verhindern Freezer.

**E2E-Beweis (Emulator, spooftest, 08/2026):** Patch mit Clone `e2e.stealth` + 3× Spoof:
- Progress-Dialog „Stealth-Modus wird angewendet / Extrahiere APK…" ✓
- `dumpsys activity services`: StealthPatchService isForeground=true, Notification-Channel
  stealth_patch ✓
- Home (App-Wechsel) → Prozess läuft weiter (pidof), DEX-Patch schreitet voran ✓
- Klon `e2e2.stealth` installiert ✓
- Rückkehr → Dialog „✓ Erfolg — Klon erfolgreich installiert: e2e2.stealth" ✓
- UI-Pitfall Emulator: Material-Switches nur über die SWITCH-Position (x≈932) sicher
  tappbar, Textmitte [540,...] trifft teils nicht; nach jedem Switch-Tap Layout neu dumpen
  (eingeblendete Felder verschieben alles darunter).

# Kern-Entscheidungen (08/2026, live verifiziert)

1. **Zwei Hebel, empirisch auf Android 15/16 (Emulator API 35) bewiesen:**
   - Ops MIT Runtime-Permission (CAMERA, RECORD_AUDIO, READ_CONTACTS, ...): `pm revoke|grant`.
     `cmd appops set` wird auf Android 15/16 für diese Ops IGNORIERT (Uid mode bleibt
     foreground/ignore) — die Permission dominiert die AppOps-Ebene. pm revoke blockt
     zuverlässig (Uid mode → ignore, granted=false), pm grant gibt frei (→ foreground).
   - Reine AppOps OHNE Permission (READ_CLIPBOARD, WRITE_CLIPBOARD): `cmd appops set ... deny|ignore|default`.
     Funktioniert direkt (verifiziert: READ_CLIPBOARD ignore).
   - "default" = nicht blockiert (grant + appops default) → Zugriff wieder normal.
2. **Shizuku-API 13.1.5 (per javap aus der AAR verifiziert — Qwen-Angaben wichen ab):**
   - `Shizuku.pingBinder()` (NICHT ping() — existiert nicht in 13.1.5)
   - `Shizuku.newProcess()` ist PRIVATE — Prozesse via
     `IShizukuService.Stub.asInterface(Shizuku.getBinder()).newProcess(cmd, null, null)`
     (AIDL-Klassen kommen transitiv über :api)
   - `ShizukuRemoteProcess.waitForTimeout(15, TimeUnit.SECONDS.name)` statt FutureTask
   - Listener-Typen: `Shizuku.OnBinderReceivedListener`/`OnBinderDeadListener` (nicht Runnable)
   - Permission-Request: `Shizuku.requestPermission(1001)`; ShizukuProvider im Manifest
     (`${applicationId}.shizuku`, permission INTERACT_ACROSS_USERS_FULL) ist Pflicht
3. **Permission-Flow VERIFIZIERT (Emulator, 08/2026):** "Verbinden" → Shizuku-Dialog
   ("Allow App Ops Shizuku to access Shizuku?") → "Allow all the time" → Status sofort
   "Shizuku bereit" (OnRequestPermissionResultListener + onResume-Refresh, KEIN Neustart).
   Fallback eingebaut: erscheint der Dialog nicht (z.B. headless-Server), wird nach 2s
   die Shizuku-App geöffnet + Hinweis-Toast. ShizukuProvider im Manifest
   (`${applicationId}.shizuku`, permission INTERACT_ACROSS_USERS_FULL) ist Pflicht.
   ⚠️ UI-Pitfall: OHNE `android:fitsSystemWindows="true"` auf den Root-Layouts liegt
   der Content in der Statusleiste (Emulator 136px) → Taps auf Buttons dort werden
   verschluckt, der Shizuku-Dialog schien nie zu kommen.
4. **Shell-Backend-Abstraktion:** NoopBackend (ohne Shizuku: nur Anzeige) / ShizukuBackend
   (exec via IShizukuService, 15s-Timeout, Strings als Array — keine Injection).
5. **AppOps-Format (Android 15/16):** `cmd appops get` liefert "Uid mode: CAMERA: foreground|ignore"
   + "CAMERA: allow" (Paket-Ebene). Parser normalisiert: foreground→allow, ignore→ignore,
   "No operations."/"No mode"→default.
6. **Feature-2-Hebel (live verifiziert, 08/2026):**
   - **App-Ebene FUNKTIONIERT für ALLE Apps:** `pm disable-user --user 0 <pkg>` (Icon weg,
     "No activity found") / `pm enable --user 0 <pkg>` — auch für User-Apps.
   - **Einzel-Komponenten NUR mit CHANGE_COMPONENT_ENABLED_STATE:** `pm disable-user --user 0
     <pkg>/<comp>` wirft auf Android 15/16 "SecurityException: Shell cannot change component
     state" für User-Apps OHNE diese signature-Permission (praktisch nur System-Apps).
     Die App zeigt dann den Hinweis-Toast.
   - ⚠️ **pm-Fehler-Erkennung:** `pm disable-user` liefert bei SecurityException **exit=0**,
     die Exception steht nur in stderr/stdout → isFailed() muss stderr/stdout auf
     "Exception"/"cannot change"/"Error" pruefen, NICHT nur exitCode.
   - **Deaktivierte Apps wiederfinden:** `pm list packages -d` (Regex "package:([\w.]+)"),
     in der App-Liste als "(deaktiviert)" mit re-aktivierbarem Einstieg.
   - **Ist-Zustand lesen:** App-Ebene via `getApplicationEnabledSetting` (App-Call),
     Komponenten via `getComponentEnabledSetting` (App-Call) — kein Shell noetig.

# Stand (08/2026)

- [x] App-Liste (Launcher-Apps) mit Icon/Label/Paket
- [x] Live-Suche (filtert waehrend des Tippens, Label + Paketname) — Emulator-verifiziert
- [x] Sortierung: Name / Installationsdatum / Aktualisierungsdatum (firstInstallTime/lastUpdateTime) — verifiziert
- [x] Spinner-Filter: Permission-Ops nur default/allow/deny (ignore == deny bei pm revoke), reine AppOps (Clipboard) alle 4 — verifiziert
- [x] Shizuku-Status-Banner (verbunden / Berechtigung fehlt / nicht verbunden) + Verbinden-Button
- [x] Permission-Flow via Shizuku-Dialog (RequestPermissionActivity) + Ergebnis-Listener + onResume-Refresh
- [x] Fallback: Shizuku-App öffnen wenn Dialog nicht erscheint (2s-Timeout)
- [x] Privacy-Profil pro App: 13 Ops mit Mode-Spinner (default/allow/ignore/deny), laden+setzen im Hintergrund-Thread
- [x] "Alle auf Standard" (Restore: Permission-Ops grant, Clipboard-Ops default)
- [x] End-to-End auf Emulator: Chrome Kamera deny → granted=false/Uid ignore; allow → granted=true/foreground
- [x] JVM-Tests 13/13 grün (Befehlsaufbau pm/appops, getMode-Parsing aller Formate)
- [x] Qwen-Review + Code-Erstellung (ShellBackend/ShizukuBackend/ShizukuManager/AppOpsController) — API-Abweichungen per javap korrigiert
- [x] **FEATURE 2 (v1.2.0): Komponenten-/Stealth-Steuerung** — ComponentsActivity:
  - App komplett deaktivieren/aktivieren (pm disable-user/enable --user 0, MaterialAlertDialog-Warnung, System-App+Eigene-App-Schutz) — VERIFIZIERT (FOSS Browser: Icon weg "No activity found" → re-aktiviert)
  - Launcher-Activities + alle Activities (GET_ACTIVITIES) mit Toggle + Start-Button (am start -f 0x10000000)
  - Receiver (GET_RECEIVERS) mit Autostart-Chip (BOOT_COMPLETED)
  - AppWidget-Provider (GET_PROVIDERS, meta-data android.appwidget)
  - Komponenten-Zustand via getComponentEnabledSetting (App-Call, kein Shell)
  - Deaktivierte Apps in der App-Liste via pm list packages -d (Label "(deaktiviert)", re-aktivierbar) — VERIFIZIERT
  - Einstieg: Komponenten-Icon in AppOpsActivity-Toolbar (setSupportActionBar + onCreateOptionsMenu)
- [x] **STEALTH-MODUS (v1.3.0): App modifizieren statt deaktivieren** — Stealth-Sektion in ComponentsActivity:
  - **Launcher-Icon dauerhaft entfernen** via AXML-Surgery (CATEGORY_LAUNCHER aus Manifest) + Repack + Neu-Signatur (apksig v1+v2+v3, bouncycastle) + pm install -r — **VERIFIZIERT auf Emulator**: FOSS Browser installiert ohne Launcher ("No activity found"), startbar über unsere App
  - **Nicht im Verlauf (Recents):** Start-Button nutzt `am start -f 0x10800000` (NEW_TASK | EXCLUDE_FROM_RECENTS) — **VERIFIZIERT**: FOSS läuft (topResumedActivity), erscheint nicht im Recents
  - **Binär-Transfer App↔Shell:** execWithStdin (Bytes über Prozess-Stdin → `sh -c "cat > /data/local/tmp/...`); App-Cache für Shell nicht lesbar (/data/user/0/<pkg> drwx------); Extraktion via pm path + cp + Base64
  - **Signaturkonflikt-Handling (v1.3.1):** Kein manuelles Deinstallieren mehr — "Anwenden" fragt per Dialog (Datenverlust-Hinweis) und deinstalliert das Original dann automatisch (pm uninstall) + installiert die modifizierte Version. Restore genauso (Stealth-Version automatisch deinstalliert, Original installiert). — VERIFIZIERT
  - **Stealth-Apps bleiben auffindbar:** markStealth() speichert gestealthte Pakete in SharedPreferences; MainActivity zeigt sie als "Label (Stealth)" — VERIFIZIERT ("FOSS Browser (Stealth)" in der Liste nach Anwendung)
  - Pipeline: stealth/BinaryXml.kt (removeCategoryLauncher) + stealth/ApkModifier.kt (Repack+Alignment) + stealth/ApkSigner.kt + stealth/StealthStealer.kt (aus AppClonerLite übernommen, angepasst)
- [x] **STEALTH v1.4.0: excludeFromRecents dauerhaft im Manifest** (User-Kernabsicht: App erscheint nicht bei den letzten Apps):
  - `BinaryXml.setExcludeFromRecents()`: String "excludeFromRecents" in den Pool (UTF-16-2-Byte-Laenge!), ResourceMap um 0x010034a3 erweitert, Attribut (TYPE_INT_BOOLEAN, Res_value: size=u16/res0/dataType/data) an die Launcher-Activity (MAIN+LAUNCHER-Finder)
  - **Verifiziert per JVM-Test + aapt2 dump xmltree**: `excludeFromRecents(0x010034a3)=(Boolean) true` (3 Tests: exclude, launcher-remove, kombiniert) — aapt2 ist der echte Android-Parser
  - **Verifiziert auf Emulator (E2E)**: FOSS normal gestartet (ohne Start-Flag!), lief im Vordergrund, NICHT im Recents
  - UI: zwei Switches — "Im Verlauf (letzte Apps) verstecken" (Default AN) + "Launcher-Icon ausblenden" (optional); Anwenden patcht beide
  - ⚠️ AXML-Pitfalls (durch aapt2-Test gefunden): UTF-16-Laenge ist 2 Byte LE; Res_value = size(u16) + res0 + dataType + data — dataType NICHT bei +14 sondern +15
- [x] **STEALTH v1.5.0: Schwärz-Modus (Inkognito, FLAG_SECURE via Dex-Patch)** — VERIFIZIERT auf Emulator:
  - `stealth/DexPatcher.kt`: baksmali → Smali der Launcher-Activity patchen (`getWindow().addFlags(0x2000)` in onCreate) → smali → Repack (ApkModifier.repackWithReplacements) → Signatur → Install. Dependencies org.smali 2.5.2 (baksmali+smali+dexlib2 einheitlich!)
  - **Verifiziert E2E**: FOSS normal gestartet, kein Crash, `dumpsys window windows` → BASE_APPLICATION-Window `fl=... SECURE ...`, Recents-Screenshot schwarz
  - OOM-Fix (on-device): `android:largeHeap="true"` im Manifest + `jobs=1` in baksmali UND smali — sonst `OutOfMemoryError ... growth limit 201326592` (192-MB-Heap, real getroffen; Host-JVM-Test läuft trotzdem durch → Host-Erfolg ≠ on-device)
  - **Zwei VerifyError-Fallen (beide real getroffen und gefixt):**
    1. Methodenname EXAKT matchen (onCreate/onStart/onResume), nicht Substring — `lambda$onCreate$0` / `onCreateOptionsMenu` haben p0=View/Menu, kein Activity → "Verifier rejected ... lambda$onCreate$0"
    2. `.registers R` → Parameter liegen in den HÖCHSTEN Registern (p0=v(R-P), P inkl. this!). Nach Erhöhung auf R+2: neue Register = v(R-P), v(R-P+1) — NICHT v(R)/v(R+1) (das sind dann p0/p1!) und nicht v(R-1) (Original-Code belegt). Fehlerbild: "'this' argument Window not instance of FragmentActivity" bzw. "tried to get class from non-reference register v13"
  - Roundtrip-Verifikation vor Gerätetest: patch → smali → baksmali zurück — neue Register müssen erhalten bleiben (nicht p0/p1 werden)
- [x] **STEALTH v1.5.1: excludeFromRecents-Fix (Root-Cause: Attribut-Sortierung!)** — VERIFIZIERT auf Emulator:
  - **Drei Bugs waren im Spiel (08/2026, alle gefixt + verifiziert):**
    1. **Falsche Resource-ID** `0x010034a3` → echte ID ist `0x01010017` (via android.jar + aapt2 link verifiziert). OxygenOS-Parser erkennt die falsche ID nicht → still ignoriert.
    2. **Boolean data=1 statt 0xFFFFFFFF** — aapt2 schreibt für true 0xFFFFFFFF; data=1 wird vom strikten System-Parser nicht übernommen.
    3. **⭐ DIE eigentliche Root-Cause (Emulator-Isolation + FlagChecker live bewiesen):** Attribut wurde ans Chunk-ENDE angehängt. aapt2 sortiert Attribute eines Elements aufsteigend nach Resource-ID (name=0x03, exported=0x10, EFR=0x17, launchMode=0x1d, ...). Der System-PackageParser (TypedArray/Styled-Attribute-Pfad) übernimmt EFR NUR an der sortierten Position. Ende-Anhängen → `EXCL=false`; sortiertes Einfügen (nach name/exported) → `EXCL=true`. Reproduziert mit Mini5b (1 Activity, 9 Attr): Ende→false, sortiert→true. aapt2-Referenz limit7 (11 Attr, EFR Pos 2) funktioniert immer. EFR VOR name → Install-Fehler `<activity> does not specify android:name`.
  - **Fix:** `addExcludeAttr` fügt das Attribut an der nach resId sortierten Position EIN (statt anhängen); ResourceMap als IntArray übergeben.
  - **Verifiziert E2E auf Emulator (zwei Wege):**
    1. JVM-Test-Pfad: echte FOSS-APK gepatcht → FlagChecker `EXCLUDE_FROM_RECENTS=true`, `dumpsys activity recents` Task `flg=0x10800000`
    2. **Kompletter App-UI-Flow (wie User):** v1.5.1 installiert → Shizuku verbunden (Toggle in Shizuku-App AN + „Allow all the time"-Dialog) → FOSS gewählt → „Anwenden" → Dialog bestätigt → Patch+Install erfolgreich → installierte FOSS-APK hat `excludeFromRecents(0x01010017)=true` → FlagChecker `EXCLUDE_FROM_RECENTS=true` → FOSS starten → Home → Recents-Screenshot: FOSS-Karte FEHLT (7 Karten, kein FOSS), `flg=0x10800000`
  - **Neues Diagnose-Werkzeug (im Skill dokumentiert):** FlagChecker-Mini-APK (queries + QUERY_ALL_PACKAGES) zeigt direkt die ActivityInfo-Flags des echten PackageManagers — aapt2-Ansicht allein reicht nicht (aapt2 liest das Attribut auch in kaputten Fällen).
  - ⚠️ Der frühere „Emulator-E2E bestanden"-Beweis (v1.4.0) war ein **False-Positive**: Die App-Karte erscheint im Recents, SOLANGE die App der fokussierte Task ist — auch MIT korrektem excludeFromRecents! Erst nach Home ist der Check aussagekräftig.
- [x] **STEALTH v1.6.0: VPN-Pflicht (Dex-Guard)** — VERIFIZIERT auf Emulator (E2E 08/2026):
  - `DexPatcher.patchSmaliVpnGuard()`: injiziert `VpnGuard.smali` in die Launcher-Activity (Aufruf nach super.onCreate) — prüft via getSystemService(connectivity) → getActiveNetwork() → getNetworkCapabilities() → hasTransport(TRANSPORT_VPN=4), bei keinem VPN `finish()` (MVP: nur Start-Guard, kein Live-Reagieren bei VPN-Verlust)
  - `BinaryXml.addUsesPermission()` patcht `ACCESS_NETWORK_STATE` ins Manifest (Pflicht — ohne sie SecurityException, real getroffen 08/2026 auf FOSS Browser)
  - UI: `stealthRequireVpnSwitch` in ComponentsActivity („Nur mit aktivem VPN ausführbar")
  - JVM-Tests: VpnGuardSmaliTest (Roundtrip nach super.onCreate, kein Doppel-Guard, Smali assembliert)
  - **E2E auf Emulator (Ziel-App FOSS Browser, VPN = Acutis Firewall v1.0.5 von F-Droid/GitHub als VpnService):**
    - Ohne VPN: App startet (am start -W „Status: ok"), nach 4s topResumedActivity = AppOpsShizuku → Guard hat gefinisht; kein Crash (AndroidRuntime leer) → PASS
    - Mit VPN: topResumedActivity + mCurrentFocus = BrowserActivity → läuft → PASS
    - Flugmodus/kein Netz: App startet nicht durch, kein Crash → PASS
    - ⚠️ `am start -W` meldet „ok" auch wenn die Activity direkt finisht — Beweis ist NUR topResumedActivity/mCurrentFocus (Qwen-Warnung bestätigt)
  - **Split-Tunnel-Erkenntnis:** Acutis routet nur DNS (8.8.8.8/32, 10.0.0.0/24), systemweiter „Active default network" bleibt WLAN — trotzdem erkannt, weil getActiveNetwork() UID-spezifisch ist und das VPN alle Uids (0-99999) abdeckt. Qwens Full-Tunnel-Warnung gilt nur für VPNs mit Teil-Uid-Sets.
- [x] **STEALTH v1.6.1: Manifest-Patch-Bugfix (Kombination Icon+VPN)** — VERIFIZIERT auf Emulator:
  - Bug: `buildStealthApk` rief `launcherActivityClass(patchedManifest)` NACH
    `removeCategoryLauncher()` auf → MAIN/LAUNCHER-Filter schon entfernt → null →
    „Fehler beim Patchen des Manifests" bei „Launcher-Icon ausblenden" + VPN-Pflicht/
    Schwärzen (real vom User gemeldet 08/2026).
  - Fix: launcherClass wird VOR den Manifest-Patches aus dem Original-Manifest bestimmt.
  - Zusätzlich: `catch` loggt jetzt Stacktrace mit Optionen (TAG „StealthStealer") statt stumm null.
  - Verifiziert E2E: FOSS mit Icon-Ausblenden+VPN-Pflicht gepatcht → lastUpdateTime geändert,
    ACCESS_NETWORK_STATE im Manifest, `No activity found` (Icon weg), ohne VPN gefinisht, kein Crash.
- [x] **VAULT-CALCULATOR (v1.7.0, Feature 5): App hinter Taschenrechner verstecken** — VERIFIZIERT auf Emulator (E2E 08/2026):
  - **Neues Modul `:vaultcalc`** (App „Rechner", Paket de.minipc.vaultcalc): funktionierende
    Taschenrechner-UI (+, -, *, /, =, C, ⌫, .), programmatisches GridLayout, Material-3-Dark-Theme.
  - **Geheimer Code (benutzerdefinierte PIN):** Zahlenfolge + „=" startet die versteckte App
    statt Rechenergebnis. PIN wird beim Patchen über ContentProvider gesetzt (Default „12345",
    4–10 Ziffern, UI-Feld in ComponentsActivity). Reine Zahlenfolge („24680=") UND getarnte
    Form mit Operator („24680+=") werden erkannt.
  - **Start der Ziel-App:** expliziter Intent mit ComponentName + FLAG_ACTIVITY_NEW_TASK |
    EXCLUDE_FROM_RECENTS | CLEAR_TASK; Shizuku-`am start`-Fallback bei nicht-exportierter
    Activity; danach `finish()` (Vault bleibt geheim).
  - **Neuer Switch „Als Taschenrechner verstecken (Vault)"** in ComponentsActivity:
    patcht Ziel-App wie Icon-Remove (Launcher-Category weg, excludeFromRecents), übergibt
    danach Ziel-Component + PIN via `VaultConfigProvider` (content://de.minipc.vaultcalc.config)
    an VaultCalc.
  - ⚠️ **Bugfix (real getroffen):** Provider schrieb in Prefs „vaultcalc", MainActivity las
    „vault" → PIN/Ziel kamen nie an (alte PIN griff weiter). Fix: beide nutzen „vault".
  - ⚠️ **Qwen-Design-Entscheidung:** KEIN activity-alias + Signature-Permission per
    AXML-Chunk-Surgery (hochriskant: String-Pool/Resource-IDs/sortierte Attribute →
    INSTALL_FAILED_INVALID_APK). Stattdessen Original-Launcher-Activity der Ziel-App als
    Vault-Einstieg — deutlich stabiler.
  - **E2E verifiziert (Emulator Android 15):** FOSS Browser mit PIN 24680 gepatcht →
    `No activity found` (Launcher-Icon weg) → VaultCalc „24680=" → topResumedActivity =
    de.baumann.browser/.activity.BrowserActivity ✓ (alte PIN 7777 griff nicht mehr nach Fix).
  - JVM-Tests weiterhin grün; VaultCalc kompiliert (Kotlin-Parser-Fix: lokale Funktionen
    nicht forward-referenzierbar → Reihenfolge parseFactor→parseTerm→parseExpression).
- [x] **DEVICE-SPOOFING (v1.8.0, Feature 6): App-spezifische Identitäts-Spoofs** — VERIFIZIERT auf Emulator (E2E 08/2026):
  - **Konzept (Qwen-Design, app-spezifisch):** KEIN System-Spoof (build.prop/GSF global = nur Root),
    sondern Smali-Call-Site-Rewriting NUR in der Ziel-App — die wird ohnehin neu signiert.
    Framework-/System-Klassen NICHT neu schreiben (ClassLoader-Parent-Delegation ignoriert
    eigene Kopien) → injizierte `DeviceSpoof.smali`-Helferklasse + Aufrufstellen umbiegen.
  - **Drei Gruppen:** (1) Build-Felder (MODEL/MANUFACTURER/BRAND/DEVICE/PRODUCT/FINGERPRINT/
    HARDWARE — sget-object-Zugriffe auf Lde/minipc/spoof/DeviceSpoof), (2) Android-ID
    (Settings$Secure;->getString → DeviceSpoof.getString, Fake nur bei "android_id" sonst Original),
    (3) IMEI/IMSI (TelephonyManager getDeviceId/getImei → telephony(), getSubscriberId →
    subscriberId(); echte Calls werfen auf Android 10+ eh SecurityException).
  - **DeviceSpoof.smali als Kotlin-Konstante in DexPatcher.kt** (wie VpnGuard — src/main/smali/
    ist KEIN Ressourcen-Pfad, getResourceAsStream schlug fehl!). Platzhalter @@MODEL@@ werden
    beim Repack mit Fake-Werten (oder Original-Build-Werten) gefüllt; smaliEscape() für Sonderzeichen.
  - **UI:** 3 Switches (Geräte-Fingerprint/Build, Android-ID, IMEI/IMSI) + 3 Eingabefelder
    (Modell/Hersteller/Fingerprint, nur bei Build-Switch sichtbar) in ComponentsActivity.
  - **JVM-Tests (DeviceSpoofSmaliTest, 5 Tests):** Regex-Rewrites (Register bleiben), kein
    Spoof = unverändert, Smali-Assemblierung der Helferklasse.
  - **E2E verifiziert (Emulator Android 15):** FOSS Browser mit MODELL="FakePhoneX1" + Android-ID +
    IMEI/IMSI gepatcht → APK extrahiert + baksmali: DeviceSpoof.smali drin mit FakePhoneX1/
    deadbeef-dead-beef-dead-beefdeadbeef/000000000000000; 11 Build-Zugriffe + 3×ANDROID_ID +
    3×IMEI + 3×IMSI in echten App-Klassen (Material/AndroidX/AppCompat) umgebogen; App startet
    via Vault (24680→BrowserActivity) ohne Crash (AndroidRuntime leer).
  - ⚠️ **Qwen-Behauptung widerlegt (live):** `cmd appops set <pkg> android:ad_id ignore` existiert
    auf API 35 NICHT (Unknown operation string). Werbe-ID-Blockade daher NICHT Teil der Smali-Patches;
    funktionierende Alternativen: GET_ACCOUNTS/READ_DEVICE_IDENTIFIERS/READ_PHONE_STATE appops.
  - ⚠️ Grenzen (Qwen + verifiziert): Reflection/JNI/native __system_property_get, Play Integrity/
    SafetyNet/Hardware-Attestation, dynamisch geladene Play-Services-Klassen (AAID-Spoof) — ohne
    Root nicht robust spoofbar. GSF-ID-Spoof (ContentResolver.query-Fake-Cursor) = Aufwand L, offen.
- [x] **DEVICE-SPOOFING v1.8.1: Zufällige Geräteprofile** (User-Wunsch 08/2026) — VERIFIZIERT auf Emulator:
  - `DeviceProfile.kt`: 12 reale Geräteprofile (Pixel 8/7/6a/8 Pro, Galaxy S23/A54/A52s, OnePlus 11,
    Xiaomi 13, Fairphone 5, Moto G84, Nothing Phone 2) mit konsistentem Set
    model/manufacturer/brand/device/product/fingerprint — NIE einzelne Random-Werte, sonst
    inkonsistent („FakePhone"+Google-Fingerprint = sofort erkennbar).
  - 🎲 Button „Zufälliges Geräteprofil wählen" in spoofBuildFields: füllt Modell/Hersteller/Fingerprint
    mit einem Zufallsprofil (Random.nextInt, variiert pro Klick).
  - `readSpoofOptions` leitet brand/device/product aus dem Fingerprint-Format ab
    („<brand>/<product>/<device>:..."), damit auch manuelle Edits konsistent bleiben.
  - **E2E verifiziert:** Klick 1 → OnePlus 11/OnePlus + voller Fingerprint; Klick 2 → Pixel 6a/Google.
    Build + JVM-Tests grün.
- [x] **SPOOF-TEST-APP (Modul :spooftest, de.minipc.spooftest)** — Qwen-Code (08/2026), E2E-BEWEIS für Device-Spoofing:
  - Minimale Test-App (1 Activity, programmatisches Layout, kein XML): zeigt Build-Felder,
    Android-ID, IMEI/IMSI/Line1 (READ_PHONE_STATE-Runtime-Permission), GSF-ID (ContentResolver),
    AAID (AdvertisingIdClient im Hintergrund-Thread — Main-Thread-Call verboten!), WLAN/BT-MAC,
    App-Signatur-SHA-256 (unterscheidet debug-neu-signiert von Original).
  - JEDER Wert einzeln try/catch → „N/A (SecurityException)" statt Crash (Android 10+ blockt IMEI).
  - Qwen-Fixes (2): `apkContentsRoots` existiert nicht → `apkContentsSigners`; Array/List-Typkonflikt
    bei Signaturen vereinheitlicht.
  - **DER E2E-BEWEIS (Emulator Android 15):** Test-App installiert (echte Werte notiert:
    sdk_gphone64_x86_64, Android-ID 2f0a02490ad0827c, IMEI=SecurityException) → in AppOpsShizuku
    mit Pixel-8-Profil (Zufalls-Button!) + Android-ID + IMEI/IMSI gepatcht → App startet:
    **MODEL=Pixel 8, DEVICE=shusky, FINGERPRINT=google/shusky/shusky:14/...,
    ANDROID_ID=deadbeef-dead-beef-dead-beefdeadbeef, IMEI/IMSI=000000000000000, 0 Crashes**.
  - GSF-ID bleibt „kein Zugriff" (geschützter Provider — erwartet, Aufwand L offen).
- [x] **APP-CLONING (v1.9.0, Feature 7): Parallele App statt Ersetzung** — VERIFIZIERT auf Emulator (E2E 08/2026):
  - **Konzept:** Ziel-App mit NEUEM Paketnamen (Default `<original>.stealth`, konfigurierbar)
    patchen → als PARALLELE App installieren. Original bleibt KOMPLETT unangetastet (eigene
    Daten, eigenes Icon, normal nutzbar). **DEFAULT = Clone an** (User-Wunsch 08/2026).
  - **`CloneRewriter.kt`:** rewriteSmaliText (Klassen-Referenzen `L<old>/` → `L<new>/` mit
    Lookbehind gegen Teilstrings, String-Konstanten `"old"`/`"old."`/`"old_"` → neue Form) +
    rewriteSmaliTree (Smali-Dateien in neues Verzeichnis verschieben). **Idempotenz-Fix:**
    ZWEI Platzhalter (Punkt- + Slash-Form) schützen bereits umbenannte Vorkommen → kein
    `.stealth.stealth` (Qwen-Code hatte das nicht sauber gelöst).
  - **`BinaryXml.renamePackage()`:** StringPool KOMPLETT neu serialisieren (newPkg länger als
    oldPkg → In-Place unmöglich); Element-Chunks bleiben (Index-basiert). **3 echte Bugs gefixt:**
    (1) Tail-Schnitt begann bei 0 statt 8 (XML-Header) → „Malformed chunk: header too long"
    (2) UTF-16-Längen little-endian schreiben (Qwen/mein Code war big-endian!)
    (3) Pool-Größe auf 4-Byte-Grenze auffüllen → „XML size ... is not on an integer boundary"
    (apksig JVM-Test grün, aber NATIVER aapt2-Parser deckte es auf — Tests nutzen jetzt aapt2!).
  - **UI:** Clone-Switch (Default an) + Paketname-Feld; eigener Bestätigungsdialog
    („PARALLELE App ... Original bleibt unangetastet"); Klon-Installation OHNE
    Original-Deinstallation (allowAutoUninstall=false); Vault: Launcher-Klasse mit-umbenannt.
  - **Tests (CloneRewriterTest, 8 Tests):** Smali-Rewrite (Klassen+Strings), Fremd-Pakete
    bleiben (browserx!), Idempotenz (2 Läufe identisch), Verzeichnis-Verschiebung,
    echtes UTF-16-Manifest (FOSS + Spooftest) mit apksig- UND aapt2-Parser-Check.
  - **E2E verifiziert:** Spooftest-App geclont → `de.minipc.spooftest` (Original, lastUpdateTime
    unverändert!) + `de.minipc.spooftest.stealth` (Klon, neu installiert) parallel installiert;
    beide starten, 0 Crashes; Klon fragt eigene Permissions (getrennte Daten).
- [x] **v1.9.1-Fix: Reines Clonen ohne Stealth/Spoof erlaubt** (User-Feedback 08/2026):
  - Vorher: Validierung in stealthApply blockierte, wenn NUR der Clone-Switch an war
    („Keine Manifest-Änderung ausgewählt") — obwohl der Clone eigene DEX-/Manifest-Arbeit macht.
  - Fix: `!cloneSwitch` in die Abbruch-Bedingung aufgenommen (1 Zeile).
  - **E2E verifiziert:** Nur Clone-Switch an, ALLE Stealth-/Spoofing-Optionen aus → Klon
    installiert, Original bleibt; beide starten; **getrennte Android-IDs bewiesen**
    (Klon `238455f784ddebd8` vs. Original `deadbeef-...` — eigene Daten!).
- [x] **v1.10.0: „Alle aktivieren/deaktivieren" pro Komponenten-Sektion** (User-Wunsch 08/2026):
  - Neue Item-Klasse `SectionActions` + ViewType + Layout `item_section_actions.xml`
    (2 Material-Buttons: „Alle aktivieren" / „Alle deaktivieren", deaktivieren in colorError).
  - `buildComponentItems()`: pro Sektion (Launcher, Activities, Receiver, Widgets) wird nach
    dem Header eine Actions-Zeile eingefügt; `toggleComponents()` loop-t über die Sektion
    (pm enable/disable-user pro Komponente, Fehler sammeln + Toast + Log, reload am Ende).
  - Fix: `toItem` gibt jetzt `ComponentListItem.ComponentItem` zurück (statt Basisklasse) —
    sonst Typkonflikt bei section-Mapping.
  - **E2E verifiziert:** Buttons sichtbar in Launcher- + Receiver-Sektion, lösen korrekt aus
    (Log: `toggleComponents(false)` + richtige Komponenten). ⚠️ Emulator-Hinweis: `pm
    disable-user` wirft auf Android 15 für die adb-Shell bei fremdsignierten Apps
    SecurityException („Shell cannot change component state") — betrifft die BESTEHENDEN
    Einzel-Toggles identisch (kein Regressions-Bug); auf OnePlus via Shizuku funktioniert es.
- [x] **v1.10.1: Clone-Label setzen** (User-Fall 08/2026: „zwei Apps die FOSS Browser heißen"):
  - Problem: `android:label` ist Ressourcen-Referenz (type=0x01 auf @string/app_name) —
    beim Klonen blieb das Label das Original → Original + Klon hießen gleich.
  - Fix: `BinaryXml.setApplicationLabel()` — Label-String in den Pool hängen, Attribut auf
    TYPE_STRING (0x03) umstellen (rawValue + data = neuer Index).
  - Logik: eigener Name im Clone-Feld → Label = sprechender Teil vor letztem Punkt
    („test.stealth" → „test"); Default (leer) → Original-Label + „ (Stealth)".
  - **E2E verifiziert (Emulator):** FOSS Browser als `test.stealth` geklont → Original
    label='FOSS Browser' (unangetastet), Klon label='test', startet, 0 Crashes.
    aapt2-Check: `application: label='test'`.
- [x] **v1.10.2: Paketname ohne Punkt automatisch erweitern** (User-Fall 08/2026:
  „Installation fehlgeschlagen" bei Eingabe nur „test"):
  - Android-Paketnamen brauchen mindestens einen Punkt (2 Segmente). Eingabe `test`
    wurde 1:1 als Paketname übernommen → Installer lehnte ab.
  - Fix: Fehlt der Punkt im eingegebenen Namen, wird `.stealth` angehängt
    (`test` → `test.stealth`); Eingaben mit Punkt bleiben unverändert. Label bleibt
    der sprechende Teil (`test` → App heißt „test").
  - **E2E verifiziert (Emulator):** FOSS Browser mit Eingabe `test` (ohne Punkt)
    geklont → Ziel `test.stealth`, Installation erfolgreich, label='test', startet,
    0 Crashes.
- [x] **v1.11.0: Design-Modernisierung nach Google Material 3** (User-Wunsch 08/2026,
  Qwen-Auftrag parallel als Vergleich gestartet):
  - **Themes:** Material-3-Farbrollen vervollständigt (colorSurfaceContainerLow/Container/
    High/Highest, colorErrorContainer, colorOutlineVariant) in Light + Dark;
    shapeAppearance-Versuche verworfen (M3-Defaults sind konsistent, Referenz-Fehler).
  - **Toolbars:** `colorPrimaryContainer` → `colorSurface` (Google-Standard: Toolbar auf
    Surface, Titel in colorOnSurface), navigationIconTint ergänzt (Main, AppOps, Components).
  - **Spinner → Material Exposed Dropdown:** `sortSpinner` (MainActivity) + `opModeSpinner`
    (AppOpsAdapter) von klassischem `<Spinner>` auf `TextInputLayout` +
    `AutoCompleteTextView` (ExposedDropdownMenuStyle) umgestellt. Kotlin: setAdapter +
    setText statt setSelection; setOnItemClickListener statt onItemSelectedListener;
    inputType=TYPE_NULL + keyListener=null (Tastatur aus, reines Auswahlfeld).
  - **Typography:** ALLE `textStyle="bold"` entfernt (LabelLarge/TitleMedium/HeadlineSmall
    haben via Material3 bereits korrektes Gewicht) — Google-Typography-Konvention.
  - **Karten:** einheitliche Corner-Radii (App-Header 16dp statt 20dp, Listen-Karten
    12dp statt 16dp), Elevation 0-1dp + Stroke statt Schatten; Header-Hintergrund
    colorSurfaceVariant → colorSurfaceContainerHigh.
  - **Suche:** startIconDrawable (Lupe) im Suchfeld; „Gesucht"-Chip blieb entfernt.
  - **E2E verifiziert (Emulator):** Main-Screen (Chips + Suche + Sortierung „Name"),
    AppOps (Exposed Dropdown öffnet als PopupWindow mit 4 Modus-Optionen, Auswahl
    feuert Listener → Toast), Komponenten (Sektionen + Alle-Buttons), 0 Crashes.
    ⚠️ Moduswechsel schlägt auf Emulator an Shell-Rechten fehl (cmd appops set auch
    direkt — identisch zum alten Spinner, kein Design-Regress).
- [ ] Auf OnePlus (Android 16/OxygenOS) testen — erwartet gleiches Verhalten, Shizuku via Wireless-Debugging; ⚠️ Emulator-verifiziert ≠ OnePlus-verifiziert (OxygenOS-Recents-Quirks, siehe v1.4.0-Hinweis)
- [ ] Feature 3 (geplant): Lifecycle-Profile (RUN_ANY_IN_BACKGROUND, Standby-Bucket, Screen-Off-Exit)

# Nutzung auf dem OnePlus

1. Shizuku installieren (shizuku.rikka.app / APK von GitHub) und via Wireless-Debugging starten
2. AppOpsShizuku installieren (debug-signiert, wie AppClonerLite)
3. Shizuku-App öffnen → "Tap to manage authorized apps" → App Ops Shizuku → Switch AN
4. App Ops Shizuku öffnen → App wählen → Privacy-Ops setzen
5. Hinweis: Kamera/Mic/Kontakte etc. = Permission-Entzug (App kann selbst reagieren, kein Crash);
   Zwischenablage = AppOps ignore (App merkt nichts, bekommt leere Daten)

# Bekannte Punkte

- Emulator: `cmd appops set` für Runtime-Permission-Ops wirkungslos → pm revoke ist der Weg (auch auf OnePlus Android 16 zu erwarten, da AOSP-Verhalten)
- POST_NOTIFICATIONS als AppOp-Name auf API 35 unbekannt (Fehler "Unknown operation string") — nicht im Profil
- requestPermission-Dialog kam auf Emulator-Headless nicht; die Shizuku-App-Autorisierung funktionierte
