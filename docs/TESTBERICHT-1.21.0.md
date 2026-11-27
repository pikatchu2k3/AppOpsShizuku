# Testbericht 1.21.0 (Teilstand)

Gerät für alle Zeilen: Redmi Note 4 (mido), LineageOS 21 (Android 14, SDK 34), adb `7724dac30504`, Shizuku 13.7.0.
Belege liegen unter `docs/evidence/1.21.0/`. Nur geprüft, was in der Tabelle steht. Alles andere ist **nicht getestet**.

| Test | Ergebnis | Beleg |
|---|---|---|
| A1/A2 Blockieren/Erlauben `READ_PHONE_STATE` (spooftest) | bestanden: `granted=false` + `USER_FIXED`, appops `ignore`; Erlauben `granted=true` | `a1-spooftest-READ_PHONE_STATE.log`, `a1-telefonstatus-allow.png` |
| A2 `pm set/clear-permission-flags` existiert, wirkt (set/grant/clear/revoke einzeln geprüft) | bestanden | Befehlsausgabe in dieser Sitzung (`CALL_PHONE` an `spooftest.perm`) |
| BUG 1 Kamera „Exit 255“ | behoben, auf Gerät bestätigt: Zeile „Kamera · nicht angefordert“, Hinweis-Toast, kein Shell-Aufruf | `bug1-nach-fix.png`, vorher `a1-kamera-exit255.png` |
| A3 Ein-Abfrage-Lesepfad vs. Einzelabfragen (`spooftest`) | Inhalt gleich: Ops ohne Zeile = „No operations“ = default. Unbekannt auf Android 14: `ACCESS_LOCAL_NETWORK`, `START_FOREGROUND_SERVICES_FROM_BACKGROUND`, `ACCESS_BACKGROUND_LOCATION` (Op-Name; Letzteres liest die App über die Permission). `ACCESS_LOCAL_NETWORK` fehlt auch in `pm list permissions -g` (0 Treffer) → „nicht setzbar“ | `a3-a4-einzelabfragen.log` |
| A3 „nicht setzbar“ in der UI als Screenshot | **nicht belegt** (UI-Treiber/Scrollen zu langsam, Timebox überschritten); nur Code-Pfad + Shell-Ausgabe | – |
| A3 Zeit | Shell, 50 Ops: Einzelabfragen ~0,96–1,02 s, eine Gesamtabfrage ~35–42 ms (als root-Shell, ohne Shizuku-Binder). **Aber:** beim ersten Öffnen im Prozess läuft je Op noch einmal die Support-Probe (einzeln), der Gewinn gilt erst ab dem zweiten Öffnen. `am start -W` Detailseite: kalt WaitTime 3012 ms (Prozessstart), warm 8 ms; sagt nichts über die Ladezeit der Liste (asynchron) | `a3-zeit-shell.log`, `a3-zeit-start.log` |
| A4 11 neue Ops: Namen gültig | bestanden: alle 10 genannten Op-Namen akzeptiert (`cmd appops get` ohne „Unknown operation“); `PrivacyOps.kt` unverändert | `a3-a4-einzelabfragen.log` |
| A4 Setzen + Zurücklesen | bestanden an einer Test-App, die die Rechte anfordert (`de.minipc.spooftest.perm`, nur lokal gebaut): `WRITE_SETTINGS`, `SCHEDULE_EXACT_ALARM`, `USE_FULL_SCREEN_INTENT`, `REQUEST_INSTALL_PACKAGES` deny/allow/default lesen sich zurück; `BLUETOOTH_CONNECT`, `READ_PHONE_NUMBERS`, `CALL_PHONE`, `SEND_SMS`, `READ/WRITE_EXTERNAL_STORAGE` grant/revoke → `Uid mode: ignore` | `a4-neue-ops-spooftest-perm.log` |
| A4 Hinweis | Reine AppOps wirken nur, wenn die App die Berechtigung deklariert: an `de.minipc.spooftest` (deklariert sie nicht) bleiben `WRITE_SETTINGS`/`SCHEDULE_EXACT_ALARM`/`USE_FULL_SCREEN_INTENT` nach `set deny` bei `default`, Exit 0. Die UI zeigt das sonst als „nicht übernommen“ (bestehender Rücklese-Check) | `a4-pure-ops-set-get.log` |
| A10 Backup-Regeln | Manifest-Regeln gelesen (Cloud-Backup: alles ausgeschlossen; Geräte-Transfer: Schlüssel/Fehlerlog ausgeschlossen). Gerät meldet `ALLOW_BACKUP` (Flag, Regeln greifen darüber). `bmgr`-Lauf **nicht** gemacht | Manifest, `res/xml/*` |
| A13 Wrapper-Prüfsumme | **Abweichung gefunden:** vorhandene Jar `d3b261c2…` (43462 B, Manifest ohne `Main-Class`) ≠ offiziell `7d3a4ac4…` (gradle.org, 8.14–8.14.3, identisch zur Jar aus github.com/gradle/gradle v8.14.0). Ersetzt durch die offizielle Jar; `./gradlew --version` → Gradle 8.14 | Commit „Wrapper-Jar …“ |

## Fehler und Fixes
1. **Kamera „Exit 255“** (nicht angeforderte Permission): `PermissionsFragment.readMode` las Permission-Ops ohne `readViaPermission` nur aus `cmd appops`, wo für nicht angeforderte Rechte keine Zeile steht → „default“ statt „nicht angefordert“. Jetzt wird zuerst die Deklaration aus `dumpsys package` geprüft. Unit-Tests in `OpEffectTest`.
2. „Nur während Nutzung“ auf nicht angeforderter Berechtigung nannte „nicht änderbar“ statt „fordert die Berechtigung nicht an“. Behoben.
3. Gradle-Wrapper-Jar war nicht die offizielle Datei (siehe A13).

## BUG 2 („nur während Nutzung“ bleibt `ignore`)
**Nicht auf dem Gerät verifiziert.** Der Auswahl-Schritt per UI-Treiber ließ sich im Zeitrahmen nicht ausführen (Zeile nicht gefunden, Zeitüberschreitung). Der Code-Befund aus der Aufgabe (Erteilung erst, wenn die Ziel-App vorne ist) bleibt unbestätigt.

## Offen (nicht bearbeitet)
A5, A6, A7, A8, A9, A11, A12, A14, Teil B (B1–B5). Frage 1–4 aus Teil C unbeantwortet, weil Teil A nicht grün abgeschlossen ist.
