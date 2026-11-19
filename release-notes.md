### English

**App Ops Shizuku 1.20.7** — "Only on demand" and "stop when the screen turns off" now work for ordinary system apps too, not just for your own apps.

**What was wrong:** the rule was "never for system apps". That is far too broad: Google Maps, Gmail or the gallery are system apps on most phones, so those two features could not be used for them at all. What matters is not whether an app ships with the phone, but whether the phone stays usable without it.

**What changed**

- Both features are now offered for normal system apps (Maps, Gmail, gallery, …).
- Still never offered, because the phone needs them: System UI, Settings, phone/dialer, SMS, providers, permission controller, the current launcher, the current keyboard, Shizuku and this app — the same protected list that the disable/remove chain uses.
- The hint text in all nine languages now says that (*"Not for apps the device needs to work …"*) instead of "not for system apps".

**Honest limits**

- **No device run.** The decision logic is covered by unit tests (one new test for system apps vs. protected packages), 352 tests in total, but whether OxygenOS actually freezes a given system app, and whether it starts again afterwards, has to be checked on the phone. Maps is the natural first candidate.
- Freezing a system app can break its background jobs (navigation, location) — that is the point of the feature, but it should be one app at a time.

**Tests:** 352 unit tests, 0 failures, 1 skipped (45 test classes).

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest — this repository is **private**; for updates through [Obtainium](https://github.com/ImranR98/Obtainium) you need a GitHub token with read access.

### Deutsch

**App Ops Shizuku 1.20.7** — „Nur bei Bedarf“ und „beim Bildschirm-Aus anhalten“ gehen jetzt auch für normale System-Apps, nicht mehr nur für eigene Apps.

**Was falsch war:** Die Regel lautete „nie für System-Apps“. Das ist viel zu grob: Google Maps, Gmail oder die Galerie sind auf den meisten Telefonen System-Apps — für die waren beide Funktionen gar nicht nutzbar. Entscheidend ist nicht, ob eine App mit dem Telefon kommt, sondern ob das Telefon ohne sie noch bedienbar ist.

**Was sich ändert**

- Beide Funktionen werden jetzt für normale System-Apps angeboten (Maps, Gmail, Galerie …).
- Weiter nie angeboten, weil das Telefon sie braucht: System-UI, Einstellungen, Telefon/Dialer, SMS, Provider, Berechtigungs-Controller, der aktuelle Launcher, die aktuelle Tastatur, Shizuku und diese App — dieselbe geschützte Liste wie bei der Deaktivieren-/Entfernen-Kette.
- Der Hinweistext sagt das jetzt in allen neun Sprachen („Nicht für Apps, die das Gerät zum Funktionieren braucht …“) statt „Nicht für System-Apps“.

**Ehrliche Grenzen**

- **Kein Gerätelauf.** Die Entscheidungslogik ist durch Unit-Tests abgedeckt (ein neuer Test für System-Apps gegenüber geschützten Paketen), insgesamt 352 Tests — ob OxygenOS eine bestimmte System-App wirklich einfriert und ob sie danach wieder startet, muss am Telefon geprüft werden. Maps ist der naheliegende erste Kandidat.
- Eine System-App einzufrieren kann ihre Hintergrundarbeit abschneiden (Navigation, Standort) — genau das ist der Zweck, aber bitte eine App nach der anderen.

**Tests:** 352 Unit-Tests, 0 Fehler, 1 übersprungen (45 Testklassen).

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest — dieses Repository ist **privat**; für Updates über [Obtainium](https://github.com/ImranR98/Obtainium) ist ein GitHub-Token mit Lesezugriff nötig.

<sub>Paket `de.minipc.appopsshizuku` · versionCode 47 · debug-signiert (updatefähig über 1.20.6) · targetSdk 35 · Lizenz GPL-3.0</sub>
