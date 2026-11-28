# Spiegelung GitHub → GitLab

- **Quelle der Wahrheit:** GitHub (`origin`, `pikatchu2k3/AppOpsShizuku`). Dort entstehen die Releases, dort greift F-Droid zu.
- **Spiegel:** GitLab (`gitlab`, `1SilentEYE1/AppOpsShizuku`), öffentlich, nur lesend gedacht.
- **Richtung:** ausschließlich GitHub → GitLab. Nichts wird je nach GitHub gepusht.

`tools/git-mirror-sync.sh` holt `origin`, vergleicht mit GitLab und pusht jeden Branch einzeln
(ein Wildcard-Push mit mehreren neuen Branches wird von GitLab mit `pre-receive hook declined` abgelehnt),
danach die Tags. Auf GitHub gelöschte Branches werden auf GitLab gelöscht.

## Divergenz
Hat GitLab Commits, die auf GitHub fehlen, bricht das Skript mit **Exit 3** ab und pusht nichts
(die Branches stehen im Log). Dann die Commits bewusst nach GitHub übernehmen oder auf GitLab verwerfen.

Exit-Codes: `0` synchron · `2` Aufruf-/Repo-Fehler · `3` Divergenz · `4` Remote-/Netzfehler.

## Installation (systemd, Benutzer-Units)
```
cp tools/systemd/git-mirror-sync.* ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now git-mirror-sync.timer
```
Der Timer läuft 5 Minuten nach dem Start und danach stündlich (`Persistent=true`).

## Log und manueller Lauf
```
tail ~/.hermes/logs/git-mirror.log     # bei > 2000 Zeilen auf die letzten 1000 gekürzt
systemctl --user list-timers git-mirror-sync.timer
tools/git-mirror-sync.sh --dry-run     # zeigt nur, was gepusht würde
tools/git-mirror-sync.sh               # echter Lauf
```
