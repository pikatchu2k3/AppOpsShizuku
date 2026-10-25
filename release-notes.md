### English

**App Ops Shizuku 1.19.1** — the screen-off guard is now unobtrusive, and it cleans up after uninstalled apps.

**Notification of the screen-off guard**
- It can be **swiped away** (Android 14+) and no longer comes back on every screen change. The service keeps running.
- A new **"Hide"** button opens the notification channel settings. That works on devices and Android versions that do not let you swipe away a service notification — the service still keeps working.

**Uninstalled apps no longer keep the service alive**
- Real case 10/2026: an app with the screen-off tick was uninstalled, but the service and its notification stayed. Only installed apps count now.
- When an app is removed, its entry is deleted; if it was the last one, the service stops and the notification disappears. A stuck notification from an older version is removed as well.
- Frozen apps that were uninstalled in the meantime are forgotten instead of being released.

**Lifecycle texts, clearer in all 9 languages**
- Title states the purpose: "Keep running in the background (profile)", plus an introduction on what it is for and how it relates to the cards above.
- Every standby level now names its concrete effect (rough waiting times), and the easily confused names "Frequently used"/"Frequent" became **"Regular"** and **"Now and then"**.
- The battery exemption hint now says that this is intentional, not an error.
- Screen-off exit is explained as the opposite of the profile; freezing and stopping are clearly separated.

**Honest limits**
- **Not tested on a device.** Open: swiping away the notification on Android 14+, the "Hide" button on devices that block swiping, and whether the service really ends after the last app is uninstalled (covered by unit tests, not by a phone).
- The notification is no longer `ongoing`, so Android may show it slightly differently. The service itself is unchanged as a foreground service.

**Tests:** 250 unit tests, 0 failures, 1 skipped. New: `deinstallierteAppHaeltDienstNichtAmLeben`.

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest — this repository is **private**; for updates through [Obtainium](https://github.com/ImranR98/Obtainium) you need a GitHub token with read access.

### Deutsch

**App Ops Shizuku 1.19.1** — der Bildschirm-Aus-Wächter ist jetzt unauffällig und räumt nach deinstallierten Apps auf.

**Meldung des Bildschirm-Aus-Wächters**
- Sie lässt sich **wegwischen** (Android 14+) und kommt nicht bei jedem Bildschirm-Wechsel neu. Der Dienst läuft weiter.
- Neuer Knopf **„Ausblenden"** öffnet die Einstellungen des Benachrichtigungskanals. Das geht auch auf Geräten und Android-Versionen, die Dienst-Meldungen nicht wegwischen lassen — der Dienst arbeitet weiter.

**Deinstallierte Apps halten den Dienst nicht mehr am Leben**
- Realer Fall 10/2026: Eine App mit dem Bildschirm-Aus-Haken wurde deinstalliert, Dienst und Meldung blieben trotzdem stehen. Jetzt zählen nur installierte Apps.
- Wird eine App entfernt, verschwindet ihr Eintrag; war sie die letzte, endet der Dienst und die Meldung geht weg. Eine hängende Meldung aus einer älteren Version wird ebenfalls aufgeräumt.
- Von uns eingefrorene Apps, die inzwischen deinstalliert wurden, werden vergessen statt freigegeben.

**Lifecycle-Texte, verständlicher in allen 9 Sprachen**
- Der Titel nennt den Zweck: „Im Hintergrund weiterlaufen lassen (Profil)", dazu eine Einleitung, wofür es ist und wie es sich zu den Karten darüber verhält.
- Jede Standby-Stufe nennt jetzt die konkrete Wirkung (ungefähre Wartezeiten), und die verwechselbaren Namen „Häufig genutzt"/„Häufig" heißen **„Regelmäßig"** und **„Ab und zu"**.
- Der Akku-Ausnahme-Hinweis sagt jetzt, dass das so gewollt ist, kein Fehler.
- Bildschirm-Aus ist als Gegenteil des Profils erklärt; Einfrieren und Beenden sind klar abgegrenzt.

**Ehrliche Grenzen**
- **Nicht am Gerät getestet.** Offen: das Wegwischen der Meldung auf Android 14+, der Knopf „Ausblenden" auf Geräten, die das Wegwischen sperren, und ob der Dienst nach der letzten deinstallierten App wirklich endet (durch Unit-Tests abgedeckt, nicht durch ein Telefon).
- Die Meldung ist nicht mehr `ongoing`, Android zeigt sie dadurch evtl. etwas anders an. Am Dienst selbst (Vordergrunddienst) ändert sich nichts.

**Tests:** 250 Unit-Tests, 0 Fehler, 1 übersprungen. Neu: `deinstallierteAppHaeltDienstNichtAmLeben`.

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest — dieses Repository ist **privat**; für Updates über [Obtainium](https://github.com/ImranR98/Obtainium) ist ein GitHub-Token mit Lesezugriff nötig.

<sub>Paket `de.minipc.appopsshizuku` · versionCode 35 · debug-signiert (Zertifikat SHA-256 `652394f7…7226e`, updatefähig über 1.19.0) · targetSdk 35 · Lizenz GPL-3.0</sub>
