# Entwurf: `PrivilegedBackend` (nur Dokument, kein Code)

Stand 1.21.0. Ziel: den Zugriff auf Shell-Rechte so kapseln, dass (a) der interne, nicht stabile
`IShizukuService` ersetzt werden kann und (b) optional ein Root-Fallback möglich ist.

## Ist-Zustand (aus dem Code)
- `shizuku/ShellBackend` ist schon die Naht: `exec`, `execTimeout`, `execWithStdin`, `suspendPackage`.
  72 Verwendungen im Code, 20 Stellen erzeugen `ShizukuBackend()` direkt.
- `ShizukuBackend.start()` holt `Shizuku.getBinder()`, wickelt es in `IShizukuService.Stub.asInterface` und ruft
  `newProcess(args, null, null)` auf. Das ist die **interne** Schnittstelle des Shizuku-Servers (AIDL im Repo
  nachgebaut, `moe.shizuku.server.*`). Sie ist nicht Teil der dokumentierten API und kann sich mit jeder
  Shizuku-Version ändern.
- `ShellProcess` (stdin/stdout/stderr/waitFor/exitValue/destroy) und `ShellRunner` sind schon Android-frei und
  unit-getestet; nur `RemoteShellProcess` hängt am Shizuku-`IRemoteProcess`.
- Jeder Befehl startet einen eigenen Prozess (`cmd appops …`, `pm …`, `dumpsys …`). Das ist der Kostentreiber
  (siehe A3/A8).

## Zielbild
```
interface PrivilegedBackend : ShellBackend {      // ShellBackend bleibt, nichts muss umbenannt werden
    val kind: Kind                                // SHIZUKU_USERSERVICE | ROOT | NONE
    fun state(): State                            // UNAVAILABLE | NO_PERMISSION | READY
    fun close()
}
```
Implementierungen:
1. **`ShizukuUserServiceBackend`** (Standard): eigener `UserService` (`Shizuku.bindUserService`, Klasse im
   eigenen APK, läuft im Shizuku-Server-Prozess mit Shell-/Root-UID). Der Dienst führt Befehle aus
   (`Runtime.exec`/`ProcessBuilder`) und liefert `ShellResult` per eigenem AIDL zurück.
   Das ist die dokumentierte, stabile Shizuku-Schnittstelle.
2. **`RootBackend`** (optional, Opt-in in den Einstellungen): `su -c` über einen langlebigen Prozess.
   Nur wenn Nutzer es wählt; nie automatisch. Kein Root-Zwang, F-Droid-neutral.
3. **`NoopBackend`** bleibt (bestehend).
Auswahl zentral in einer `BackendProvider.get(context)`-Fabrik statt 20× `ShizukuBackend()`.

## Vorteile
- Weg vom internen `IShizukuService`: weniger Bruchrisiko bei Shizuku-Updates.
- Ein langlebiger Dienst kann **mehrere Befehle in einem Prozess** ausführen (Batch): `cmd appops get` je App
  und `pm`-Aufrufe ohne Prozessstart pro Befehl. Das ist der eigentliche Geschwindigkeitsgewinn.
- Sauberer Ort für Timeouts und Fehlerprotokoll.

## Aufwand (grobe Schätzung)
| Schritt | Aufwand |
|---|---|
| `PrivilegedBackend` + `BackendProvider`, 20 Aufrufstellen umstellen (mechanisch) | 0,5 Tag |
| UserService (AIDL, Bind/Unbind, Lebenszyklus, Reconnect nach Shizuku-Neustart) | 2–3 Tage |
| Batch-Befehl (mehrere Argumentlisten pro Aufruf) und Umbau der Lesepfade | 1–2 Tage |
| Root-Fallback samt Einstellung und Warntext | 1 Tag |
| Tests (Fake-Backend vorhanden) + Gerätetests Android 8–15 | 2 Tage |

## Risiken
- **UserService-Lebenszyklus:** Der Dienst läuft im Server-Prozess; nach Neustart/Stopp von Shizuku ist die
  Bindung weg. Reconnect-Logik muss die heutigen Zustände (`ShizukuManager.onStateChanged`) erhalten.
- **Doppelter Code im Server-Prozess:** Der Dienst läuft mit hohen Rechten; er darf nur feste, von der App
  validierte Befehle ausführen (Eingabevalidierung wie `ImportGuard`/Paketnamen-Prüfung), sonst wird er zur
  offenen Shell für jede App mit Shizuku-Recht.
- **Root-Fallback:** Sicherheits- und Vertrauensfrage (Root-Shell für eine Datenschutz-App); nur Opt-in. Auf F-Droid
  unkritisch, aber Support-Aufwand.
- **Verhalten pro Android-Version:** Prozess-UID (shell vs. root) beeinflusst Ausgabe von `dumpsys`/`appops`
  (auf dem Testgerät läuft der Server als root, auf adb-Geräten als shell). Gerätetests nötig.
- **APK-Größe/Minify:** UserService-Klasse muss in R8-Keep-Regeln (`app/proguard-rules.pro`).

## Migration in Schritten (je einzeln lauffähig)
1. `BackendProvider` einführen, Aufrufer umstellen, Verhalten unverändert (reiner Refactor, Tests grün).
2. UserService hinter einem Debug-Schalter parallel zum alten Weg; beide Wege per Gerät vergleichen.
3. Standard auf UserService, alten `IShizukuService`-Pfad nach einer Release-Runde entfernen.
4. Batch-API und Lesepfade darauf umstellen.
5. Root-Fallback als Opt-in.

## Empfehlung
Schritt 1–3 lohnen sich (Stabilität). Root-Fallback nur auf ausdrücklichen Wunsch. Entscheidung offen beim Nutzer.
