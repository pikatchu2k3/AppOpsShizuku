# App Ops Shizuku 1.18.1

Diese Fassung holt zwei Versionen nach, die zwar gebaut, aber nie veröffentlicht wurden —
bis hierhin war **v1.17.0** die neueste Version auf GitHub.

## 1.18.1 (33)

**Schalter reagieren nur noch auf echte Nutzer-Tipps.** Bisher haben sich die Umschalter in den
Komponenten-Ansichten selbst ausgelöst, wenn die App sie programmatisch gesetzt hat (Listener wurden
dafür ab- und wieder angebunden). Jetzt gibt es dafür `ToggleGuard`/`GuardedSwitch`: programmatische
Änderungen laufen „still", nur ein echter Fingertipp löst die Aktion aus. Das verhindert, dass ein
Zustand versehentlich doppelt gesetzt oder eine Änderung zurückgenommen wird.

## 1.18.0 (32)

**Mehrsprachige Auslieferung:** 314 Texte als englische Basis plus acht Übersetzungen (Deutsch,
Spanisch, Französisch, Hindi, Japanisch, Portugiesisch (Brasilien), Russisch, Chinesisch (vereinfacht)).
Zeilenumbrüche in den Sprachdateien sind als `\n` escaped (kanonische Form).

## Prüfung

- Eigener Clean-Build aus dem committeten Stand: `BUILD SUCCESSFUL`, 79 Tasks, **238 Tests, 0 Fehler**
- APK: `AppOpsShizuku-v1.18.1.apk`, 34.945.352 Bytes
  MD5 `3a6a73d7fd1ecbb3f0457c27a218d0fb`
  SHA-256 `354c18d958858d7ad0503815d5d7f4b01fd498cd4cedf5f7653bddc9b4200489`

## Hinweis zum Aktualisieren

Wer die App über einen Update-Tracker (GitHub-Quelle) bezieht, hat bisher **v1.17.0** als „neueste
Version" angezeigt bekommen, obwohl auf dem Gerät schon 1.18.x lief — ein Tipp auf Installieren hätte
**zurückgestuft**. Mit diesem Release ist die Lücke geschlossen: „neueste Version" ist jetzt 1.18.1.
