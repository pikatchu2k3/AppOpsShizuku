# App Ops Shizuku

[English](README.md) · **Deutsch**

App Ops Shizuku steuert Berechtigungen, App Ops und Hintergrundverhalten **anderer Apps** auf deinem Android-Handy über [Shizuku](https://shizuku.rikka.app/). **Ohne Root, ohne Xposed.** Gedacht für alle, die sehen und einschränken wollen, was installierte Apps dürfen (Kamera, Mikrofon, Kontakte, Zwischenablage, Netz im Hintergrund, …), ohne das Gerät zu rooten.

## Voraussetzungen

- Android 8.0+ (`minSdk 26`, siehe `app/build.gradle.kts`)
- Ein laufender **Shizuku**-Server (z. B. per Wireless-Debugging gestartet)
- Erteilte Shizuku-Berechtigung für App Ops Shizuku

Ohne Shizuku *zeigt* die App nur an und schaltet nichts.

## Installation / Update

APK aus dem [neuesten GitHub-Release](https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest) laden.

**F-Droid:** Die App ist für [F-Droid](https://f-droid.org) vorbereitet (Build aus dem Quellcode, Metadaten unter `fastlane/`, siehe [docs/fdroid](docs/fdroid/README.md)). F-Droid signiert mit eigenem Schlüssel; ein Wechsel zwischen F-Droid- und GitHub-Build braucht eine Neuinstallation (vorher die Sicherung exportieren).

**Zwei Ausgaben** mit derselben App-ID: **full** (wie bisher, mit WLAN-Regeln, die Standort-Rechte brauchen, um den WLAN-Namen zu lesen) und **lite** (gar keine Standort-Rechte, keine WLAN-Regeln). Das GitHub-Release ist *full*.

Automatische Updates: Repository in [Obtainium](https://github.com/ImranR98/Obtainium) eintragen (Quelle: *GitHub Releases*, URL `https://github.com/pikatchu2k3/AppOpsShizuku`). Die Signatur bleibt gleich, deshalb wird ein Update direkt über die installierte App eingespielt, die Daten bleiben erhalten.

## Auf einen Blick

App Ops Shizuku ist ein **Datenschutz-Steuerpult für die anderen Apps auf deinem Handy**. Es kann:

- **Sehen und einschränken**, was jede App darf: 49 Berechtigungen und App Ops (Kamera, Mikrofon, Standort, Kontakte, Kalender, Anrufliste, SMS, Zwischenablage, Medien, Hintergrund-Lauf, Overlays, …) — mit einem **Zugriffsprotokoll**, das zeigt, wann eine App sie zuletzt benutzt hat und welche Versuche abgewehrt wurden.
- **Nur bei Bedarf erteilen**: *nur während Nutzung* (die Berechtigung gibt es nur, solange die App vorne ist), *nur bei Bedarf* (die App bleibt geschlossen, bis du sie öffnest) und ein *Schnell-Lockdown*, der allen Apps auf einmal Kamera und Mikrofon entzieht.
- **Netzzugriff kappen**: Hintergrunddaten sperren, eine App komplett offline nehmen, den **Verkehr je App** sehen (Hintergrund getrennt) und **privates DNS** (mit Tracker-Blocker-Vorlagen) für das ganze Handy umschalten.
- **Automatisieren**: Regeln nach Zeit oder WLAN, Quarantäne für neu installierte Apps, Profil-Vorlagen per Tipp, eine Schnelleinstellungs-Kachel.
- **Herausfinden, was Apps tun**: wer das Gerät weckt, wer Jobs plant, wer Sensoren abfragt, wer Bedienungshilfen- oder Benachrichtigungszugriff hat.
- **Apps abschotten**: in ein Arbeitsprofil verschieben (die App kann selbst eins anlegen).
- **Apps umschreiben (Beta)**: aus dem Verlauf ausblenden, Vorschaubilder schwärzen, Geräte-IDs fälschen, Berechtigungen oder Tracker-Komponenten entfernen, Platzhalter-Daten liefern, parallele Klone installieren.
- **Die Kontrolle behalten**: jede Änderung wird vom Gerät zurückgelesen, steht in einem **Änderungsprotokoll mit „Rückgängig"**, und die App hat selbst **keine Internet-Berechtigung** — nichts verlässt das Handy.

## Wo finde ich was?

Die App hat unten in einer schwebenden Leiste drei Bereiche: **Apps**, **Werkzeuge** und **Einstellungen**. Ein Tipp auf eine App öffnet ihre Detailseite mit den Reitern *Berechtigungen*, *Verhalten*, *Tarnung* und *Netz & Tracker*.

| Funktion | Wo |
|---|---|
| Berechtigungen je App, Zugriffsprotokoll, Empfehlungen, Vorlagen, nur während Nutzung | App → *Berechtigungen* |
| Einfrieren, Deaktivieren, Daten löschen, Standby-Stufe, Akku-Ausnahme, nur bei Bedarf | App → *Verhalten* |
| Hintergrunddaten, Offline, Sperrliste, Tracker-Erkennung | App → *Netz & Tracker* |
| Patch, Klon, Ausblenden, Fälschen, Tracker abschalten, Berechtigungen entfernen | App → *Tarnung* |
| Suchen, Sortieren, Filtern (*Alle / Blockiert / Geändert*) | Knöpfe in der Kopfzeile von *Apps* |
| Nach Berechtigung · Netzverkehr · Aktivität · Automatische Regeln · Systemweit · Arbeitsprofil · Änderungsprotokoll · Diagnose | *Werkzeuge* |
| Shizuku-Status, Quarantäne, Systemfarben, Sicherung, Dauerrecht | *Einstellungen* |
| Lockdown | *Werkzeuge → Systemweit* und als Schnelleinstellungs-Kachel |

## Funktionen

### App-Liste
- Live-Suche (Name und Paketname), Sortierung nach Name / Installations- / Aktualisierungsdatum, Filter *Alle*, *Blockiert* (mindestens eine Berechtigung mit dieser App auf Ignorieren/Verweigern gestellt) und *Geändert* (irgendetwas von dieser App geändert).
- Eine **Statuszeile** auf jeder Karte zeigt, was diese App an ihr geändert hat: blockierte Berechtigungen, offline, keine Hintergrunddaten, nur bei Bedarf, nur während Nutzung, Quarantäne. Sie kommt aus den eigenen Speichern der App, ein Shell-Aufruf ist nicht nötig.
- Verlaufskarten in der Farbe des jeweiligen App-Icons auf einem ruhigen Sternenhimmel (hell und dunkel). Solange Shizuku nicht verbunden ist, erscheint ein Hinweis.

### Berechtigungen je App (Reiter *Berechtigungen*)
- **49 Datenschutz-Berechtigungen und Spezialzugriffe** in neun Gruppen — *Sensoren* (Kamera, Mikrofon, Körper-Sensoren, Aktivitätserkennung, Bluetooth-Suche, WLAN-Geräte in der Nähe, lokales Netzwerk), *Standort* (genau, ungefähr, im Hintergrund, Foto-Standort), *Daten* (Kontakte, Kalender, Anrufliste, Zwischenablage, Telefonstatus, SMS), *Medien* (Fotos, Videos, Audio, ausgewählte Medien, alle Dateien), *Konten*, *Hintergrund* (Hintergrund-Ausführung, Vordergrunddienste aus dem Hintergrund, Nutzungsstatistik), *Identität* (Geräte-IDs, Standort vortäuschen) *System-UI* (Overlay, Benachrichtigungen, Bildschirm einschalten, Bild-in-Bild) und *Spezialzugriff* (unbekannte Apps installieren, Systemeinstellungen, exakte Alarme, Vollbild-Benachrichtigungen).
- Jede lässt sich auf Standard / erlauben / ignorieren / verweigern setzen. Berechtigungen mit Laufzeit-Erteilung nutzen `pm grant|revoke` (Blockieren setzt zusätzlich `user-fixed`, die App kann also nicht einfach erneut fragen); reine App Ops nutzen `cmd appops set`. Ops, die das Gerät nicht kennt, erscheinen als „nicht setzbar", Berechtigungen, die die App nie anfordert, als „nicht angefordert".
- **Alles blockieren** und **Alle auf Standard** (ein Reset erteilt nie etwas). Profile werden gespeichert und als JSON exportiert.
- **Zugriffsprotokoll**: letzte Nutzung, abgewehrte Versuche und „wird gerade benutzt" je Berechtigung, aus einem einzigen `cmd appops get`.
- **Empfehlungskarte**: Berechtigungen, die erteilt, aber seit 30 Tagen ungenutzt oder für die App-Kategorie unüblich sind (Spiele, Medien, Nachrichten). Geändert wird erst nach Tipp auf *Entziehen*.
- **Profil-Vorlagen**: *Spiel*, *Messenger* und *Banking* per Tipp; das aktuelle Profil lässt sich als JSON teilen.
- **Nur während Nutzung**: zusätzlicher Modus für Berechtigungen, für die Android keinen Vordergrund-Modus kennt (Kontakte, Kalender, Anrufliste, Medien, Konten, …). Erteilt, solange die App vorne ist; entzogen 10 s nach dem Verlassen oder beim Bildschirm-Aus (Android beendet die App dann im Hintergrund).
- Hinweis zur Zwischenablage: `deny` kann eine App beim Start abstürzen lassen — *ignorieren* (stille Blockade) ist die bessere Wahl, davor warnt die App.

### Über alle Apps (*Werkzeuge*)
- **Nach Berechtigung**: eine Berechtigung wählen und sehen, welche Apps sie haben und wann sie sie zuletzt benutzt haben; je App umschalten oder allen Nutzer-Apps auf einmal entziehen (System-Apps werden nie angefasst).
- **Lockdown**: entzieht allen Nutzer-Apps Kamera und Mikrofon (wahlweise Standort) und gibt sie *genau* diesen Apps zurück. Auch als **Schnelleinstellungs-Kachel**.
- **Quarantäne für neue Apps** (*Einstellungen*, standardmäßig aus): nach dem Einschalten installierte Apps bekommen keine Hintergrunddaten, keine Zwischenablage und keinen Hintergrund-Lauf, bis du sie freigibst (Karte *Freigeben* im Reiter *Berechtigungen* der App). Apps in Quarantäne bleiben dort, auch wenn du die Quarantäne ausschaltest.
- **Automatische Regeln**: nach **Zeit** (Wochentage + Fenster, auch über Nacht) oder **WLAN** (mit einem Netz verbunden / nicht verbunden). Aktion: Lockdown oder gewählte Apps offline nehmen. Der WLAN-Name wird erst gelesen, nachdem du dieser App bei der ersten Nutzung die Standortberechtigung erteilt hast (sie hat selbst keinen Netzzugriff). Verbirgt Android den Namen, solange die App im Hintergrund läuft, wird die Regel übersprungen — nicht abgeschaltet —, bis du die App öffnest. Regeln brauchen ein laufendes Shizuku.

### Verhalten (Reiter *Verhalten*)
- App deaktivieren, **einfrieren** (`pm suspend`, mit eigenem Text im Systemdialog), Daten löschen (Bestätigung per Paketname), Standby-Stufe setzen, Akku-Ausnahme (Doze) erteilen/entfernen, „App optimieren" (neu kompilieren).
- **Lifecycle-Profil**: Akku-Ausnahme + Standby-Stufe + Hintergrund-Ops in einem Schritt, zurücknehmbar. Optional **App beim Bildschirm-Aus anhalten** (solange aktiv, läuft ein Vordergrunddienst).
- **Nur bei Bedarf:** Die App bleibt geschlossen und gesperrt (`am force-stop` + `pm suspend` oder `pm disable-user` oder **nur beenden** – allein `am force-stop`, damit die App weiter aus jedem Launcher wie Nova startbar ist; der Wächter erkennt den Start und beendet sie nach dem Verlassen wieder) und läuft nur, nachdem du sie über ihre eigene Verknüpfung auf dem Startbildschirm geöffnet hast. Nach dem Verlassen wird sie nach 10 s / 1 Min. / 5 Min. oder nur beim Bildschirm-Aus wieder gesperrt; das Einschalten des Bildschirms gibt sie nie frei. Das Verlassen wird über Nutzungsereignisse erkannt (Nutzungszugriff erteilt sich die App per Shizuku), sonst über `dumpsys activity`. Zum Öffnen muss Shizuku laufen; bei *Deaktivieren* lässt sich die App auch ohne Shizuku in den System-Einstellungen wieder aktivieren. Solange sie gesperrt ist, kommen Nachrichten, Wecker und Hintergrund-Sync der App nicht an. Hinweis zu *Einfrieren*: `pm suspend` sperrt nur das Öffnen; startet das System oder eine andere App sie im Hintergrund (Job, Bluetooth-Gerät, gebundener Dienst), läuft sie trotz Einfrieren. Der Wächter prüft deshalb alle 5 Minuten (nur eingefrorene Apps) und beim Bildschirm-Aus, ob eine eingefrorene App den Beendet-Zustand verlassen hat, und beendet sie erneut; ganz verhindert solche Weckrufe nur *Deaktivieren*. **Eigene Starter** (z. B. AppDialer NXT) können eine eingefrorene App über die exportierte Aktion `de.minipc.appopsshizuku.action.OPEN_ON_DEMAND` (Extra `pkg`) öffnen; der Aufrufer muss `startActivityForResult` nutzen, damit Android meldet, wer anfragt, und jeder neue Starter wird einmal gefragt (*Immer erlauben* / *Nur diesmal* / *Ablehnen*; im Reiter *Verhalten* widerrufbar).
- **Für welche Apps:** *Nur bei Bedarf* und *Beim Bildschirm-Aus anhalten* gehen auch für vorinstallierte System-Apps wie Google Maps. Ausgenommen sind nur Apps, die das Gerät zum Funktionieren braucht: System-UI, Einstellungen, Telefon, Provider, Berechtigungs-Controller, der aktuelle Launcher und die Tastatur, Shizuku und diese App.
- **Komponenten**: Liste der Launcher-Einträge, Activities, Receiver (auch Autostart) und Widgets der App, mit *Alle deaktivieren / Alle aktivieren* je Abschnitt. Einzelne Komponenten darf die Shell nur bei Apps mit `CHANGE_COMPONENT_ENABLED_STATE` schalten (meist System-Apps) — sonst sagt der Bildschirm das (siehe *Ehrliche Grenzen*); für das Icon *App deaktivieren* nutzen.
- **Wenn der Hersteller das Deaktivieren sperrt** (z. B. OnePlus/OxygenOS beantwortet `pm disable-user` mit *new state: default*): Jedes Deaktivieren wird vom Gerät zurückgelesen. Hat es nicht gewirkt, startet *Lässt sich nicht deaktivieren? Andere Wege …* eine Kette und stoppt beim ersten wirksamen Schritt: 1. `pm disable-until-used`, 2. **für diesen Nutzer entfernen** (`pm uninstall -k --user 0` – APK und Daten bleiben; die App erscheint als *(entfernt)* in der Liste und wird mit `cmd package install-existing` wiederhergestellt), 3. **als Geräteinhaber verstecken** (nur wenn eingerichtet, siehe unten), 4. **neutralisieren** (einfrieren + beenden + Hintergrund-Ops verweigert + Standby-Stufe *eingeschränkt*). Jeder Schritt wird mit ✓/✗ und dem Grund des Geräts angezeigt. System-UI, Einstellungen, Telefon, Provider, Berechtigungs-Controller, der aktuelle Launcher und die Tastatur, Shizuku und diese App werden nie angeboten.
- **Geräteinhaber (optional, Einstellungen):** `dpm set-device-owner` aus der Shell erlaubt dieser App, Apps über `DevicePolicyManager.setApplicationHidden` zu verstecken – ein anderer Weg, den manche Hersteller nicht sperren. Android lässt das nur zu, wenn **keine Konten** und **kein zweiter Nutzer/kein Arbeitsprofil** auf dem Gerät sind; die App entfernt selbst nichts und zeigt die genaue Antwort des Geräts. *Abgeben* holt zuerst alle versteckten Apps zurück und gibt dann den Geräteinhaber ab. Dasselbe geht vom PC: `scripts/device-owner.sh check|set|remove` (adb).

### Netz & Tracker
- **Je App** (Reiter *Netz & Tracker*): Hintergrunddaten sperren · App komplett offline nehmen · auf Androids Hintergrund-Sperrliste setzen · **Tracker-Erkennung** (liest das Manifest und durchsucht den Code nach bekannten Bausteinen: Firebase Analytics / DataTransport / Crashlytics, Facebook SDK und Audience Network, AdMob, Google Analytics, Sentry, Mixpanel, Segment, Amplitude, Braze, AppsFlyer, Adjust, Kochava). Jeder Schalter wird durch Zurücklesen geprüft; „Alles zurücksetzen" macht nur rückgängig, was diese App selbst geändert hat.
- **Netzverkehr** (*Werkzeuge*): Verkehr je App über 7 Tage, Hintergrund getrennt. Apps mit viel Hintergrundverkehr, die man selten öffnet, stehen oben, mit „Hintergrund sperren" per Tipp.
- **Privates DNS** (*Werkzeuge → Systemweit*): aus / automatisch / Vorlagen (AdGuard, Mullvad adblock, Quad9, Cloudflare) / eigener Host, mit „Zurück auf vorher".

### Einblick (*Werkzeuge*)
- **Aktivität**: welche Apps das Gerät wecken, Jobs planen oder Sensoren abfragen (aus `dumpsys`, heuristisch).
- **Änderungsprotokoll**: jede Änderung dieser App, Berechtigungs- und Netz-Schalter je Eintrag *rückgängig*.
- **Diagnose**: harmlose Prüfungen zeigen, welche Hebel auf *deinem* Gerät funktionieren (Permission-Flags, Sperrdialog-Text, Firewall-Kette, `dumpsys`-Formate, Arbeitsprofil). Der Bericht lässt sich als Text teilen; er enthält Modell und Android-Version, keine App-Liste und keine persönlichen Daten.
- **Fehlerprotokoll** (*Werkzeuge → Diagnose*): zeichnet Abstürze, fehlgeschlagene Shizuku-Befehle und interne Fehler auf, nur auf dem Gerät (höchstens ~200 KB, die ältesten Einträge fallen heraus, Wiederholungen innerhalb von 10 Minuten werden einmal geschrieben). Nach einem Absturz oder Fehler bietet eine Benachrichtigung *Teilen* und *Speichern* an; nach einem Absturz fragt die App beim nächsten Start außerdem, ob du zur Diagnose willst. Der Bericht enthält App-Version, Gerätemodell, Android-Version, das letzte Diagnose-Ergebnis und das Protokoll, auch die Paketnamen der Apps, bei denen etwas fehlschlug. Es wird nichts automatisch gesendet (keine Internet-Berechtigung); du entscheidest, wohin er geht. Der Benachrichtigungskanal *Fehler* lässt sich in den Systemeinstellungen abschalten.
- **Neustart-Hinweis**: nach einem Neustart (Shizuku läuft noch nicht) meldet eine Benachrichtigung, dass der eingerichtete Schutz inaktiv ist; nach dem Verbinden bietet eine Leiste an, abgewichene Einstellungen wiederherzustellen.

### Systemweit und mächtige Rechte (*Werkzeuge → Systemweit*, *Arbeitsprofil*)
- Übersicht **mächtiger Rechte**: Bedienungshilfen, Benachrichtigungszugriff, Tastaturen, Standard-Apps (SMS, Telefon, Assistent, Browser), dauerhaftes VPN und Geräteadministratoren — entziehbar, wo die Shell es darf, sonst ein Knopf zu den Systemeinstellungen.
- **Dauerrecht** (*Einstellungen*): die App erteilt sich einmal über Shizuku `WRITE_SECURE_SETTINGS`; privates DNS und das Entziehen von Bedienungshilfen gehen danach auch nach einem Neustart ohne Shizuku.
- **Arbeitsprofil**: die App kann selbst ein verwaltetes Profil anlegen (eigener Profilbesitzer) und Apps hineinverschieben; in ein vorhandenes Profil (z. B. Shelter) verschiebt sie Apps auch allein mit Shell-Rechten. Verschobene Apps sehen weder die Kontakte noch die Dateien des Hauptprofils und starten mit eigenen Daten.

### Tarnung — Patch und Klon (Beta, Reiter *Tarnung*)
- (Die Schalter liegen derzeit noch im Bildschirm *Komponenten*.) Patcht die APK einer App auf dem Gerät und signiert sie neu: aus dem Verlauf ausblenden, Launcher-Icon entfernen, Verlaufs-Vorschau schwärzen (`FLAG_SECURE`), VPN-Pflicht, als Taschenrechner tarnen (Vault), app-spezifisches Geräte-Spoofing (Build-Felder, Android-ID, IMEI/IMSI) oder als **parallelen Klon** installieren. Der Vault fragt nach einer PIN (4–10 Ziffern; **leer = `12345`** — eigene setzen). Beim Geräte-Spoofing gibt es *Zufälliges Geräteprofil*; Hersteller, Modell und Fingerprint lassen sich auch eintippen. *Wiederherstellen* installiert die gesicherte Original-APK erneut. Eine Fortschritts-Benachrichtigung zeigt die Phase (Entpacken, Code patchen, Packen, Signieren, Installieren, Nachweis, Starttest) und lässt sich abbrechen.
- **Tracker abschalten**, **Berechtigungen entfernen** (Internet, Werbe-ID, App-Liste, Standort, Telefonstatus, Kontenliste) und **Platzhalter-Daten statt Sperre** (Null-Werbe-ID, fester Ort auf Stadtebene) — Details und Grenzen unter *In Entwicklung*.

### Einstellungen und Sicherung
- Shizuku-Status, Quarantäne-Schalter, *Systemfarben (Material You)*, **Dauerrecht**, Export / Import und die App-Version.
- Die **Sicherung** ist eine JSON-Datei, gespeichert wo du willst (der Speichern-Dialog schlägt `appopsshizuku-backup-<Datum>.json` vor, z. B. in *Downloads*): Privacy-Profile, Verhaltens-Einstellungen, Lifecycle-Profile, Automatik-Regeln, „Nur während Nutzung", der Quarantäne-Schalter und die Lockdown-Auswahl. Vor dem Anwenden zeigt der Import, was in der Datei steht, und verwirft ungültige Einträge sowie Deaktivieren/Einfrieren geschützter System-Apps. Ältere Dateien bleiben lesbar; beim Import werden nur bekannte Berechtigungen akzeptiert. Der momentane Gerätezustand (welche Apps gerade in Quarantäne stehen) wird bewusst nicht gesichert. Für Skripte und Tasker geht der Import auch ohne Dateidialog: `am start -n de.minipc.appopsshizuku/.MainActivity --es import_file <Pfad>`.

## Sicherheitsprinzipien

- **Keine Internet-Berechtigung**: die App kann nichts irgendwohin senden; ihre Daten liegen im eigenen privaten Speicher.
- **Nichts wird angenommen**: jede Änderung wird vom Gerät zurückgelesen und nur als erledigt gemeldet, wenn sie wirklich wirkt.
- **Alles ist umkehrbar**: Änderungsprotokoll mit „Rückgängig", „Alles zurücksetzen" macht nur rückgängig, was diese App selbst geändert hat, die Original-APKs werden vor einem Patch gesichert.
- **Standort nur bei Bedarf**: die Berechtigung wird erst bei der ersten WLAN-Regel angefragt, nie beim Start. Beim Speichern einer WLAN-Regel erteilt sich die App zusätzlich per Shizuku den Hintergrund-Standort (Best-Effort), weil die Regel im Hintergrund ausgewertet wird; ohne WLAN-Regeln hält die App nie eine Standortberechtigung.
- **Quelloffen** unter GPL-3.0.

Was als *Beta* gekennzeichnet oder in den Release-Notizen als neu beschrieben ist, wurde nicht auf jedem Gerät überprüft — mit der *Diagnose* siehst du, welche Hebel auf deinem funktionieren.

## Ehrliche Grenzen

- **Einzelne Komponenten fremder Apps lassen sich zur Laufzeit nicht abschalten.** Android 15/16 verweigert das der Shell (`SecurityException: Shell cannot change component state`) — deshalb gibt es dafür bewusst keinen Schalter. Der Reiter Tarnung umgeht das anders: Er bearbeitet das Manifest einer neu signierten Kopie (siehe *In Entwicklung*).
- Die Netzschalter stoppen **Netzverkehr**, nicht den Tracker-Code in der App. Werbung/Analysen, die die App selbst im eigenen Prozess startet, laufen weiter. Echten Code entfernt nur der APK-Patch im Reiter Tarnung.
- Die Tracker-Erkennung kennt nur Bausteine mit Manifest-Komponenten. **„Nicht gefunden" heißt nicht „nicht enthalten".**
- „Komplett offline" ist bei System-Apps nicht möglich (der Schalter ist dort deaktiviert).
- *Daten löschen* gibt es nicht für System-Apps und nicht für diese App.
- Manche Berechtigungen lassen sich bei manchen Apps/Geräten nicht ändern (z. B. fordert die App sie nicht an, oder das Gerät lehnt die Op ab); die App sagt das, statt Erfolg vorzutäuschen.
- **Ein PC hat nicht mehr Rechte als Shizuku.** adb läuft als derselbe Shell-Nutzer; was der Hersteller der Shell verweigert (z. B. `pm uninstall --user` → *only root can delete system app for a particular user*), verweigert er auch vom PC. Die einzige echte Stufe mehr ohne Root ist der Geräteinhaber. *Neutralisieren* ist schwächer als Deaktivieren: Das System kann eine eingefrorene App weiter im Hintergrund starten.

## In Entwicklung (Beta)

Der Reiter Tarnung (Patch, Klon, Tracker abschalten) ist **in Entwicklung**. Er trägt einen festen Hinweis im Reiter, im Bestätigungsdialog und im Ergebnis.

- **In Entwicklung (Beta).** Patch und Abschalten können die App beschädigen. Wichtig ist eine Sicherung der App-Daten, bevor du patchst.
- Die gepatchte App wird mit **unserem eigenen Schlüssel** signiert — **nicht** mit dem Schlüssel des Herstellers bzw. von Google. Deshalb: **keine Play-Updates mehr**, Play Protect kann warnen, und zurück zur Original-App geht es nur über Neuinstallation (*Wiederherstellen* installiert die gesicherten Original-APKs neu; die App-Daten bleiben dabei nicht erhalten).

**Tracker abschalten (Beta)** — drei Ankreuzfelder (*Werbung*, *Nutzungsdaten & Absturzberichte*, *Installations-Zuordnung*) und eine Zeile mit den erkannten Bausteinen der App:
1. Komponenten der gewählten Gruppen bekommen im gepatchten Manifest `android:enabled="false"`. Nichts wird gelöscht: Einträge und Klassennamen bleiben, SDK-Startpfade brechen nicht; das System instanziiert sie nur nicht mehr.
2. Für Firebase werden die offiziell dokumentierten Schalter als letzte Kinder von `<application>` angehängt (sie gewinnen gegen einen früheren Eintrag): `firebase_analytics_collection_enabled=false`, `firebase_analytics_collection_deactivated=true`, `firebase_crashlytics_collection_enabled=false` — nur, wenn das SDK erkannt wurde.
3. Eine **Code-Stufe gibt es nicht**: ungetestete Smali-Eingriffe sind nicht erlaubt.
- Nie angefasst, auch wenn der Katalog sie kennt: Push (`FirebaseMessagingService`, `…MessagingService` von Drittanbietern), WorkManager (`androidx.work.*`), Bezahlung (`com.android.billingclient.*`), Google-/Facebook-Login und der DataTransport-Scheduler (gemeinsame Infrastruktur von Push und Absturzberichten).
- **Beweis:** Das gepatchte APK wird mit dem eingebauten Katalog gescannt (Manifest und Code-Scan) und mit dem Original verglichen: „Bausteine vorher: 5 — nachher: 2 — abgeschaltet: …".
- **Rückweg:** Vor dem Patch werden die Original-APK(s) gesichert; das Ergebnis nennt den Ordner („Original gesichert: …"). Ein abgebrochener Patch ändert nichts an der installierten App.
- **Startprobe:** Nach der Installation wird die gepatchte App einmal gestartet und ca. 15 s lang das `logcat` gelesen (nur die eigenen Prozesse der App): `FATAL EXCEPTION`, `ClassNotFoundException`, `NoSuchMethodError`, `VerifyError`, `SecurityException`. Ein Absturz wird als „App stürzt nach dem Patch ab" gemeldet, andere Treffer als Warnung. Kein stiller Erfolg. Hat die App keine Launcher-Activity oder lässt sie sich nicht starten, steht im Ergebnis, dass **nicht** geprüft wurde.

**Berechtigungen entfernen (Beta)** — sechs Ankreuzfelder und eine Zeile, welche dieser Berechtigungen die App überhaupt deklariert:
- *Kein Internet (Offline-Klon)* `INTERNET` · *Keine Werbe-ID* `com.google.android.gms.permission.AD_ID` und die Privacy-Sandbox-Rechte `ACCESS_ADSERVICES_*` · *Installierte Apps nicht sichtbar* `QUERY_ALL_PACKAGES` · *Kein Standort* `ACCESS_FINE/COARSE/BACKGROUND_LOCATION` · *Kein Telefonstatus / keine Rufnummer* `READ_PHONE_STATE`, `READ_PHONE_NUMBERS` · *Keine Kontenliste* `GET_ACCOUNTS`.
- Die `<uses-permission>`-Einträge (auch `uses-permission-sdk-23`) werden aus dem gepatchten Manifest entfernt. Anders als *Entziehen* im Reiter Berechtigungen ist das eine Eigenschaft der installierten APK: Es übersteht Neustarts, wirkt ohne laufendes Shizuku, und die App kann nicht erneut fragen (`requestPermissions` liefert ohne Dialog „abgelehnt"). Ohne `INTERNET` kann die UID der App überhaupt keine Netz-Sockets öffnen.
- **Beweis:** Das Manifest der **signierten** APK wird mit dem Original verglichen. Steht eine gewählte Berechtigung noch drin, wird nichts installiert. Danach läuft dieselbe Startprobe wie bei den Trackern.
- Unterhalb eines bestimmten `targetSdk` von der Plattform nicht zugesagt und im Ergebnis so gemeldet: Nullen-Werbe-ID erst ab targetSdk 33 (Google-Doku), Filter der App-Liste erst ab targetSdk 30.
- Ohne Klon ersetzt die gepatchte App das Original (Datenverlust, siehe oben). Mit „abgelehnt" muss die App selbst zurechtkommen; Apps, die Internet brauchen, funktionieren ohne nicht mehr.
- **Noch nicht am Gerät getestet** — bisher nur Unit-Tests mit aapt2 an synthetischen und echten Manifesten.

**Platzhalter-Daten statt Sperre (Beta)** — zwei Schalter im Geräte-Spoofing, nur in der gepatchten App bzw. im Klon:
- *Werbe-ID*: `AdvertisingIdClient$Info.getId()` liefert eine Null-ID, `isLimitAdTrackingEnabled()` liefert `true`.
- *Standort*: `LocationManager.getLastKnownLocation(String)` liefert einen festen Ort auf Stadtebene (Standard Berlin, 52.52 / 13.405). Hilft bei Apps, die abstürzen, wenn der Standort verweigert ist.
- Umgesetzt durch Umschreiben der Aufrufstellen im App-Code (smali), dieselbe Technik wie beim Geräte-ID-Spoofing. Nicht abgedeckt: andere Standort-Wege (`getCurrentLocation`, Fused Location) und andere Werbe-ID-Pfade. Leere Kontakte werden bewusst **nicht** angeboten (zu bruchgefährdet). Noch nicht am Gerät getestet.

**Ehrliche Grenzen des Patches**
- Der Code abgeschalteter Bausteine bleibt in der APK. Bausteine ohne Manifest-Komponenten (Segment, Amplitude, altes Google Analytics, Werbe-ID-Bibliothek, Facebook-App-Events) lassen sich so nicht abschalten.
- „Abgeschaltet" heißt im Beweis: alle abschaltbaren Manifest-Komponenten dieses Bausteins sind deaktiviert. Das ist eine Aussage über das Manifest, keine Garantie, dass nichts mehr gesendet wird.
- Für das Abschalten wird nur das Manifest der Basis-APK bearbeitet. Ein Split mit eigenem Code lässt einen Klon mit klarer Meldung scheitern (die Paket-Umbenennung im Code deckt nur die Basis-APK ab).
- Die Startprobe ist eine Log-Heuristik: Eine abgefangene `SecurityException` erscheint als Warnung, obwohl die App läuft.

**Am echten Gerät gemessen (Android 14, 3,5 GB RAM, Testgerät — keine Schätzwerte)**
- Patch ohne Klon (nur Manifest), 39-MB-App: Extrahieren → Repack → Signieren → Installieren in rund 90 s. Das ist der schnelle Weg.
- Klon/Code-Umbau (baksmali + smali) ist CPU-gebunden und auf schwacher Hardware langsam: Eine 9-MB-App lief schon mehrere Minuten in `DEX_PATCH` (rund 2,8 Kerne Dauerlast, ~500 MB Speicher); eine 37-MB-App kam in neun Minuten nicht über `DEX_PATCH 40 %`. Auf dem Testgerät sind Klone nur für kleine Apps praktikabel — die Fortschritts-Benachrichtigung zeigt die Phase, ein Abbruch lässt die installierte App unangetastet.
- Beobachtetes Ergebnis an einer 37-MB-App (8 gefundene Bausteine): *vorher: 8 — nachher: 6 — abgeschaltet: Firebase Analytics, Facebook Audience Network*, dazu die drei Firebase-Schalter; ausdrücklich weiter aktiv und so gemeldet: AdMob, Werbe-ID-Bibliothek, Braze, Adjust, Datentransport, Crashlytics. Bausteine, die der Katalog nicht kennt, werden gar nicht erfasst (realer Fall: `com.microsoft.clarity.jobs.UploadSessionJob` blieb aktiv).
- Die Startprobe ist auf 90 s begrenzt und blockiert den Lauf nie; sieht sie den Prozess der App nicht, sagt sie „nicht aussagekräftig" statt Erfolg zu behaupten.

**Grenzen des Klons**
- Firebase (`google_app_id`) und AdMob (App-ID) sind an Paketname und Signatur gebunden — der Klon kann Registrierungsfehler melden oder keine Werbung erhalten.
- Apps mit Integritätsprüfung (Play Integrity) können den Start verweigern.
- Der Klon startet mit **eigenen Daten** (kein Login-/Verlaufs-Transfer).
- Vor dem Patch wird der Ziel-Paketname geprüft: Ist er von einer anderen App belegt, meldet der Reiter das und es wird nichts gepatcht. Ein früherer Klon dieser App wird an Ort und Stelle aktualisiert.

## Fehler melden

Öffne ein [Issue](https://github.com/pikatchu2k3/AppOpsShizuku/issues/new/choose) mit dem Formular *Fehler melden*. Am meisten hilft der Fehlerbericht aus der App: *Werkzeuge → Diagnose → Fehlerprotokoll → Fehlerbericht teilen* (oder *Als Datei speichern* und anhängen). Lies ihn vor dem Posten durch: Er enthält Gerätemodell, Android-Version und die Paketnamen der Apps, bei denen etwas fehlschlug.

## Selbst bauen

```bash
export ANDROID_HOME=/pfad/zum/android-sdk     # oder sdk.dir in local.properties
./gradlew :app:assembleFullDebug              # oder :app:assembleLiteDebug
```

JDK 17 oder neuer genügt (das Projekt zielt auf Java 17); feste Rechnerpfade sind nicht nötig. Das APK liegt unter `app/build/outputs/apk/<ausgabe>/debug/`. Tests: `./gradlew :app:testFullDebugUnitTest :app:testLiteDebugUnitTest`, Lint: `:app:lintFullDebug :app:lintLiteDebug`. `./gradlew :app:assembleRelease` baut unsignierte Release-APKs (das baut F-Droid). Optionales R8-Verkleinern: `-Pminify=true` (auf dem Gerät nicht geprüft, standardmäßig aus). `scripts/release.sh` baut, testet, prüft die Signatur und veröffentlicht ein GitHub-Release (für den Maintainer; liest `ANDROID_HOME`, optional `ARTIFACT_DIR`).

Das Repository enthält **keine vorkompilierten Binärdateien** (nur den Standard-Gradle-Wrapper). Die Begleit-App des Taschenrechner-Vaults (`de.minipc.calcyou`, ein eigenes GPL-3.0-Projekt) wird nicht mehr mitgeliefert; fehlt sie nach einem Vault-Patch, weist das Ergebnis darauf hin, sie separat zu installieren.

## Design und Farben

Die App nutzt eine **vollständige Material-3-Palette**, systematisch aus einer ruhigen Petrol-Startfarbe abgeleitet (hell und dunkel), Rot bleibt echten Fehlern vorbehalten. *Einstellungen* → **Systemfarben (Material You)**: an (Standard) = Farben aus dem Hintergrundbild des Systems (Android 12+), aus = die eigene Petrol-Palette; die Wahl bleibt gespeichert. Auf Android 11 und älter ist der Eintrag ausgegraut. Gemessener Kontrast: schlechtester Textwert 6,49:1 (hell) / 5,46:1 (dunkel) — Ziel war 4,5:1. App-Liste und Werkzeuge nutzen Verlaufskarten auf einem **bewegten** Sternenhimmel (funkelnde Sterne, sehr langsames Treiben, ab und zu eine Sternschnuppe; er läuft nur bei sichtbarem Bildschirm mit etwa 30 Bildern pro Sekunde, stoppt von selbst im Energiesparmodus oder bei abgeschalteten Systemanimationen und lässt sich unter *Einstellungen* → *Bewegter Hintergrund* ausschalten); die Kartenfarbe stammt vom jeweiligen App-Icon und wird so weit abgedunkelt, dass weiße Schrift mindestens 4,5:1 Kontrast hat (per Unit-Test abgesichert).

## Signatur

GitHub-Releases sind Release-Builds (nicht debuggable), signiert mit dem Debug-Zertifikat, SHA-256 `652394f7…7226e` (vollständig: `652394f75d9ed1ea168561e4056556fd82d3645a6c88020cd30b18c0a3d7226e`). Ein Update lässt sich nur mit **gleicher Signatur** über eine bestehende Installation einspielen; ein selbst gebautes APK mit anderem Debug-Schlüssel aktualisiert die veröffentlichte App nicht (erst deinstallieren, dabei gehen die App-Daten verloren).

## Lizenz

Dieses Projekt steht unter der **GNU General Public License, Version 3.0** — vollständiger Text in [LICENSE](LICENSE).

Der Taschenrechner-Vault arbeitet mit einem CalcYou-Fork (GPL-3.0) zusammen. Da die App ohnehin öffentlich verteilt wird, steht das ganze Projekt unter derselben Lizenz.

Weil das Repository öffentlich ist, ist der Quellcode zu jedem veröffentlichten Paket an derselben Stelle verfügbar (Repository + Releases). Es gibt **keine Gewährleistung**: Das Patchen fremder Apps kann sie beschädigen, der Tarnungs-Reiter wird auf eigenes Risiko benutzt.

## Sprachen

Die Oberfläche gibt es auf Englisch (Basis) und in 8 Übersetzungen — je 764 Texte, vollständig (jeder Text übersetzt, keine Lücken).

| Sprache | Locale | Abdeckung |
|---|---|---|
| German/Deutsch | `values-de` | 764/764 (100%) |
| Spanish/Español | `values-es` | 764/764 (100%) |
| French/Français | `values-fr` | 764/764 (100%) |
| Hindi/हिन्दी | `values-hi` | 764/764 (100%) |
| Japanese/日本語 | `values-ja` | 764/764 (100%) |
| Portuguese (Brazil) | `values-pt-rBR` | 764/764 (100%) |
| Russian/Русский | `values-ru` | 764/764 (100%) |
| Chinese (Simplified)/简体中文 | `values-zh-rCN` | 764/764 (100%) |

Alle Übersetzungen sind maschinell geprüft (Platzhalter, Pluralformen, XML-Struktur); `R.array.*`-Ressourcen bleiben unberührt. Englisch ist die Basis: Ein Gerät mit nicht unterstützter Sprache zeigt Englisch, nicht Deutsch.
