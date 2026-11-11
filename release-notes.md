### English

**App Ops Shizuku 1.20.3** — the big privacy package: diagnostics, standing access without Shizuku, access log, quarantine, app traffic, automatic rules, work profile and a full backup.

**New**

- **Diagnostics** (Tools): harmless checks show which levers work on this device — permission flags, freeze-dialog text, firewall chain, `dumpsys` formats, work profiles. The report can be shared as text; it contains model and Android version, no app list and no personal data.
- **Standing access** (Settings): the app grants itself `WRITE_SECURE_SETTINGS` once via Shizuku; private DNS and revoking accessibility services then keep working after a reboot without Shizuku.
- **Full backup**: also covers automatic rules, “only while in use”, the quarantine switch and the lockdown selection.
- **Access log** (Permissions tab): last use, refused attempts and “in use right now” per permission from a single `cmd appops get`, plus a recommendation card for permissions granted but unused for 30 days. Nothing changes until you tap *Revoke*.
- **Only while in use**: an extra mode for permissions without a foreground mode (contacts, calendar, call log, media, accounts) — granted while the app is in front, revoked 10 s after leaving or on screen-off.
- **By permission** (menu): pick one permission and see which apps hold it and when they last used it; flip per app, or revoke for all user apps at once (never system apps).
- **Status line in the app list** (blocked, offline, no background data, on demand, only while in use, quarantine) and a *Changed* filter chip — from the app's own stores, no shell call.
- **Quarantine for new apps** (menu, off by default): apps installed while it is on get no background data, no clipboard and no background run until you release them.
- **System-wide** (menu): *Lockdown* removes camera/microphone (optionally location) from all user apps and gives it back to exactly those apps — also as a quick-settings tile. *Private DNS* with presets (AdGuard, Mullvad adblock, Quad9, Cloudflare) and “back to previous”. *Powerful access*: accessibility services, notification access, keyboards, default apps, always-on VPN, device admins — revocable where the shell is allowed, otherwise a button into system settings.
- **Change log** (menu): every change made by this app, with *Undo* per entry.
- **Profile templates** (Permissions tab): game / messenger / banking in one tap, shareable as JSON.
- **App traffic** (menu): traffic per app over 7 days, background separated; apps with heavy background traffic you rarely open are listed first, with “block background” one tap away.
- **Activity** (menu): which apps wake the device, schedule jobs or query sensors (from `dumpsys`, heuristic).
- **Automatic rules** (menu): by time (days + window, across midnight) or Wi-Fi (connected / not). Action = lockdown or selected apps offline. The Wi-Fi name is only read after you granted this app location once; if Android hides it while the app is in the background, the rule is skipped, not switched off.
- **Work profile** (menu): App Ops Shizuku can create a managed profile itself (it becomes the profile owner) and move apps into it; into an existing profile (e.g. Shelter) it can move apps with shell rights alone.
- **Placeholder data instead of blocking (beta patch)**: a repacked clone returns a null advertising ID and a city-level location instead of the real values, so apps that crash on “denied” keep working.
- **Restart notice**: after a reboot a notification points out that the configured protection is inactive; once connected, a bar offers to restore settings that drifted.
- **Design “Starry sky”**: gradient cards, glass header, floating navigation, carried through to all sub-screens.
- **Lock type “Stop only”**: an app stays locked and can still be started from any launcher; it gets its own lock dialog.
- **Granting a permission** now clears `user-fixed`, blocking sets it — the app cannot simply ask again.

Unchanged from 1.20.2: external launchers may open on-demand apps, and the wake watchdog keeps running every 5 minutes for frozen apps.

**Honest limits**

- **Not tested on a device.** Verified: the merge of both feature lines, build, 344 unit tests, debug signature, and that no strings were lost (659 → 693 keys in all 9 languages).
- **Placeholder data is a beta patch** and can disturb apps that expect real values.
- **Work profile** depends on the device allowing profile creation; in an existing profile only the move works.
- **Automatic rules with Wi-Fi** need location permission for this app, and newer Android versions hide the network name from background apps.
- Standing access weakens the model on purpose: with `WRITE_SECURE_SETTINGS` granted, settings can be changed without Shizuku running.

**Tests:** 344 unit tests, 0 failures, 1 skipped (44 test classes).

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest — this repository is **private**; for updates through [Obtainium](https://github.com/ImranR98/Obtainium) you need a GitHub token with read access.

### Deutsch

**App Ops Shizuku 1.20.3** — das große Datenschutz-Paket: Diagnose, Dauerrecht ohne Shizuku, Zugriffsprotokoll, Quarantäne, Netzverkehr, automatische Regeln, Arbeitsprofil und vollständige Sicherung.

**Neu**

- **Diagnose** (Werkzeuge): harmlose Prüfungen zeigen, welche Hebel auf diesem Gerät funktionieren — Permission-Flags, Sperrdialog-Text, Firewall-Kette, `dumpsys`-Formate, Arbeitsprofil. Der Bericht lässt sich als Text teilen; er enthält Modell und Android-Version, keine App-Liste und keine persönlichen Daten.
- **Dauerrecht** (Einstellungen): die App erteilt sich einmal über Shizuku `WRITE_SECURE_SETTINGS`; privates DNS und das Entziehen von Bedienungshilfen gehen danach auch nach einem Neustart ohne Shizuku.
- **Vollständige Sicherung**: enthält jetzt auch Automatik-Regeln, „Nur während Nutzung", den Quarantäne-Schalter und die Lockdown-Auswahl.
- **Zugriffsprotokoll** (Reiter Berechtigungen): letzte Nutzung, abgewehrte Versuche und „wird gerade benutzt" je Berechtigung aus einem einzigen `cmd appops get`; dazu eine Empfehlungskarte für Rechte, die erteilt, aber seit 30 Tagen ungenutzt sind. Geändert wird erst nach Tipp auf *Entziehen*.
- **Nur während Nutzung**: zusätzlicher Modus für Berechtigungen ohne Vordergrund-Modus (Kontakte, Kalender, Anrufliste, Medien, Konten) — erteilt, solange die App vorne ist, entzogen 10 s nach dem Verlassen oder beim Bildschirm-Aus.
- **Nach Berechtigung** (Menü): eine Berechtigung wählen und sehen, welche Apps sie haben und wann sie sie zuletzt benutzt haben; je App umschalten oder allen Nutzer-Apps auf einmal entziehen (System-Apps nie).
- **Statuszeile in der App-Liste** (blockiert, offline, keine Hintergrunddaten, nur bei Bedarf, nur während Nutzung, Quarantäne) und Filter-Chip *Geändert* — aus den eigenen Speichern, ohne Shell-Aufruf.
- **Quarantäne für neue Apps** (Menü, standardmäßig aus): nach dem Einschalten installierte Apps bekommen keine Hintergrunddaten, keine Zwischenablage und keinen Hintergrund-Lauf, bis du sie freigibst.
- **Systemweit** (Menü): *Lockdown* entzieht allen Nutzer-Apps Kamera/Mikrofon (wahlweise Standort) und gibt sie genau diesen Apps zurück — auch als Schnelleinstellungs-Kachel. *Privates DNS* mit Vorlagen (AdGuard, Mullvad adblock, Quad9, Cloudflare) oder eigenem Host, mit „Zurück auf vorher". *Mächtige Rechte*: Bedienungshilfen, Benachrichtigungszugriff, Tastaturen, Standard-Apps, dauerhaftes VPN, Geräteadministratoren — entziehbar, wo die Shell es darf, sonst ein Knopf zu den Systemeinstellungen.
- **Änderungsprotokoll** (Menü): jede Änderung dieser App, je Eintrag *rückgängig*.
- **Profil-Vorlagen** (Reiter Berechtigungen): Spiel / Messenger / Banking per Tipp, als JSON teilbar.
- **Netzverkehr** (Menü): Verkehr je App über 7 Tage, Hintergrund getrennt; Apps mit viel Hintergrundverkehr, die man selten öffnet, stehen oben, mit „Hintergrund sperren" per Tipp.
- **Aktivität** (Menü): welche Apps das Gerät wecken, Jobs planen oder Sensoren abfragen (aus `dumpsys`, heuristisch).
- **Automatische Regeln** (Menü): nach Zeit (Tage + Fenster, auch über Nacht) oder WLAN (verbunden / nicht), Aktion = Lockdown oder gewählte Apps offline. Der WLAN-Name wird erst gelesen, nachdem du der App einmal Standort erteilt hast; verbirgt Android ihn im Hintergrund, wird die Regel übersprungen, nicht abgeschaltet.
- **Arbeitsprofil** (Menü): App Ops Shizuku kann selbst ein verwaltetes Profil anlegen (eigener Profilbesitzer) und Apps hineinverschieben; in ein vorhandenes Profil (z. B. Shelter) verschiebt es Apps auch allein mit Shell-Rechten.
- **Platzhalter-Daten statt Sperre (Beta-Patch)**: der neu signierte Klon liefert eine Null-Werbe-ID und einen Ort auf Stadtebene statt der echten Werte, damit Apps, die bei „verweigert" abstürzen, weiterlaufen.
- **Neustart-Hinweis**: nach einem Neustart meldet eine Benachrichtigung, dass der eingerichtete Schutz inaktiv ist; nach dem Verbinden bietet eine Leiste an, abgewichene Einstellungen wiederherzustellen.
- **Design „Sternenhimmel"**: Verlaufskarten, Glas-Kopfzeile, schwebende Navigation — auch auf allen Unterseiten.
- **Sperrart „Nur beenden"**: eine App bleibt gesperrt und ist trotzdem aus jedem Launcher startbar; eigener Sperrdialog gehört dazu.
- **Erteilen** einer Berechtigung hebt jetzt `user-fixed` auf, Blockieren setzt es — die App kann nicht einfach erneut fragen.

Aus 1.20.2 unverändert enthalten: fremde Starter dürfen Bedarfs-Apps öffnen, Weck-Wächter alle 5 Minuten für eingefrorene Apps.

**Ehrliche Grenzen**

- **Nicht am Gerät getestet.** Belegt: Zusammenführung beider Feature-Linien, Build, 344 Unit-Tests, Debug-Signatur und dass keine Strings verloren gingen (659 → 693 Keys in allen 9 Sprachen).
- **Platzhalter-Daten sind ein Beta-Patch** und können Apps stören, die echte Werte erwarten.
- **Arbeitsprofil** hängt davon ab, ob das Gerät Profilanlage erlaubt; in einem vorhandenen Profil geht nur das Verschieben.
- **Automatische Regeln mit WLAN** brauchen die Standortberechtigung für diese App; neuere Android-Versionen verbergen den Netzwerknamen vor Hintergrund-Apps.
- Das Dauerrecht weicht das Modell bewusst auf: mit `WRITE_SECURE_SETTINGS` lassen sich Einstellungen ohne laufendes Shizuku ändern.

**Tests:** 344 Unit-Tests, 0 Fehler, 1 übersprungen (44 Testklassen).

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest — dieses Repository ist **privat**; für Updates über [Obtainium](https://github.com/ImranR98/Obtainium) ist ein GitHub-Token mit Lesezugriff nötig.

<sub>Paket `de.minipc.appopsshizuku` · versionCode 43 · debug-signiert (Zertifikat SHA-256 `652394f7…7226e`, updatefähig über 1.20.2) · targetSdk 35 · Lizenz GPL-3.0</sub>
