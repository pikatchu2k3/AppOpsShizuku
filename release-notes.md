### English

**App Ops Shizuku 1.17.0** — new colour design following Google's Material 3 guidelines, plus a switch for system colours.

**New colour system**
- The app now uses a **complete** Material 3 palette that is **derived systematically** from a calm petrol/teal seed (`#006874`): seed → CIELAB → tonal palettes per colour family → roles by the official table (light *and* dark). No more half-defined baseline colours.
- **Red is reserved for real errors now.** Until this release `colorError` was used for states like "blocked" or "denied" in six layouts — that was the harsh look. Blocked = calm tertiary/secondary container, active = primary container, unknown = neutral, and **one** amber warning role for risky (but not failed) actions.
- **Adaptive launcher icon** with a monochrome layer for themed icons (Android 13+) — the shield-and-keyhole motif stays, now in the palette instead of hard-coded blue.
- **Edge-to-edge** on all screens with matching system bars, and monochrome icons that follow the theme (in dark mode they used to be hard-coded black).
- Measured contrast (WCAG, computed in a test): worst text pair **6.49:1** light and **5.46:1** dark — target was 4.5:1. A pixel check of the screens shows **0.00 % harsh red**.

**New switch: system colours (Material You)**
- Menu of the main list → *System colors (Material You)*: on = the app takes its colours from the system wallpaper (Android 12+), off = the app's own petrol palette. The choice is remembered and takes effect immediately.
- On Android 11 and older the entry is greyed out and labelled accordingly — the petrol palette always applies there.
- Measured on a test device: switch on → resolved colours come from the system (`primary #006973`), switch off → from the app palette (`primary #036874`). On a wallpaper that is not teal the difference is clearly visible.

**Honest limits**
- Material You exists from Android 12 on. Below that the app palette is the only option.
- The switch lives in the menu of the main list; screens that are already open keep the old colour choice until they are reopened.
- Colours are not a proof: this release changes how the app looks, not what it can do.

**Still from the previous releases**
- Tracker overview reads manifest **and** code (DEX) and names the source per entry.
- Stealth tab (in development / beta): tracker switch-off while patching, plus cloning with its own package name; patched apps are signed with **our own key**, not with the manufacturer's or Google's.
- License: **GNU General Public License v3.0**.

Download: this repository is **private again** — releases are available to you only; for updates through [Obtainium](https://github.com/ImranR98/Obtainium) you need a GitHub token.

Build: `:app:assembleDebug` · package `de.minipc.appopsshizuku` · versionCode 31 · debug signature (certificate SHA-256 `652394f7…7226e`).
License: **GNU General Public License v3.0** — see [LICENSE](https://github.com/pikatchu2k3/AppOpsShizuku/blob/master/LICENSE).

### Deutsch

**App Ops Shizuku 1.17.0** — neues Farbsystem nach Googles Material-3-Richtlinien, dazu ein Schalter für Systemfarben.

**Neues Farbsystem**
- Die App nutzt jetzt eine **vollständige** Material-3-Palette, **systematisch** abgeleitet aus einer ruhigen Petrol-Startfarbe (`#006874`): Seed → CIELAB → Tonalpaletten je Farbfamilie → Rollen nach der offiziellen Tabelle (hell **und** dunkel). Keine halb definierten Vorgabefarben mehr.
- **Rot ist jetzt echten Fehlern vorbehalten.** Bisher wurde `colorError` in sechs Layouts für Zustände wie „gesperrt" oder „verweigert" benutzt — das war der harte Eindruck. Gesperrt = ruhige Tertiär-/Sekundärfläche, aktiv = Primärfläche, unbekannt = neutral, und für riskante (nicht fehlgeschlagene) Aktionen gibt es **eine** amber Warnfarbe.
- **Adaptives Launcher-Icon** mit Monochrom-Layer für Themed Icons (Android 13+) — das Schild-mit-Schlüsselloch-Motiv bleibt, jetzt in der Palette statt in fest verdrahtetem Blau.
- **Edge-to-edge** auf allen Ansichten mit passenden Systemleisten; die Icons folgen dem Theme (im Dunkelmodus waren sie vorher fest schwarz).
- Kontrast gemessen (WCAG, im Test berechnet): schlechtester Textwert **6,49:1** hell und **5,46:1** dunkel — Ziel war 4,5:1. Die Pixelprüfung der Bildschirme ergibt **0,00 % grelles Rot**.

**Neuer Schalter: Systemfarben (Material You)**
- Menü der Hauptliste → *Systemfarben (Material You)*: an = die App übernimmt die Farben aus dem Hintergrundbild des Systems (Android 12+), aus = die eigene Petrol-Palette. Die Wahl bleibt gespeichert und wirkt sofort.
- Auf Android 11 und älter ist der Eintrag ausgegraut und entsprechend beschriftet — dort gilt immer die Petrol-Palette.
- Am Testgerät gemessen: Schalter an → aufgelöste Farben kommen vom System (`primary #006973`), Schalter aus → aus der App-Palette (`primary #036874`). Bei einem Hintergrundbild, das nicht petrolfarben ist, sieht man den Unterschied deutlich.

**Ehrliche Grenzen**
- Material You gibt es erst ab Android 12. Darunter bleibt nur die App-Palette.
- Der Schalter liegt im Menü der Hauptliste; bereits geöffnete Ansichten behalten die alte Farbwahl, bis sie neu geöffnet werden.
- Farben sind kein Nachweis: Dieses Release ändert das Aussehen, nicht die Fähigkeiten der App.

**Weiterhin aus den vorigen Releases**
- Tracker-Übersicht liest Manifest **und** Code (DEX) und nennt je Eintrag die Herkunft.
- Reiter Tarnung (in Entwicklung/Beta): Tracker beim Patchen abschalten, dazu Klonen mit eigenem Paketnamen; gepatchte Apps werden mit **unserem eigenen Schlüssel** signiert, nicht mit dem des Herstellers oder von Google.
- Lizenz: **GNU General Public License v3.0**.

Download: Dieses Repository ist **wieder privat** — die Releases sind nur für dich erreichbar; für Updates über [Obtainium](https://github.com/ImranR98/Obtainium) brauchst du einen GitHub-Token.

Build: `:app:assembleDebug` · Paket `de.minipc.appopsshizuku` · versionCode 31 · Debug-Signatur (Zertifikat SHA-256 `652394f7…7226e`).
Lizenz: **GNU General Public License v3.0** — siehe [LICENSE](https://github.com/pikatchu2k3/AppOpsShizuku/blob/master/LICENSE).
