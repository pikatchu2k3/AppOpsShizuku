#!/usr/bin/env bash
# Spiegelt GitHub (origin, Quelle der Wahrheit) nach GitLab (gitlab). Pusht nie in Richtung GitHub.
# Aufruf: git-mirror-sync.sh [--dry-run] [--repo <pfad>]
# Exit-Codes: 0 = synchron, 2 = Aufruf-/Repo-Fehler, 3 = Divergenz (GitLab hat Commits, die GitHub fehlen),
#             4 = Remote-/Netzfehler (fetch oder push)
set -euo pipefail

repo=/home/mini/AppOpsShizuku
dry=0
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) dry=1 ;;
    --repo) repo="${2:?--repo braucht einen Pfad}"; shift ;;
    *) echo "Unbekanntes Argument: $1" >&2; exit 2 ;;
  esac
  shift
done

log=$HOME/.hermes/logs/git-mirror.log
mkdir -p "$(dirname "$log")"
say() {
  printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" | tee -a "$log" >&2
}
if [ -f "$log" ] && [ "$(wc -l < "$log")" -gt 2000 ]; then
  tail -n 1000 "$log" > "$log.tmp" && mv "$log.tmp" "$log"
fi

cd "$repo" 2>/dev/null || { say "Repo nicht gefunden: $repo"; exit 2; }
export GIT_TERMINAL_PROMPT=0
[ "$dry" = 1 ] && say "Trockenlauf: es wird nichts gepusht"

git fetch --prune --tags origin >/dev/null 2>&1 || { say "FEHLER: fetch origin fehlgeschlagen"; exit 4; }
git fetch --prune gitlab '+refs/heads/*:refs/remotes/gitlab/*' >/dev/null 2>&1 \
  || { say "FEHLER: fetch gitlab fehlgeschlagen"; exit 4; }

# Divergenz: GitLab-Branch hat Commits, die im GitHub-Stand desselben Branches fehlen.
diverged=""
while read -r ref; do
  b=${ref#refs/remotes/gitlab/}
  [ "$b" = HEAD ] && continue
  if git show-ref --verify --quiet "refs/remotes/origin/$b" \
     && [ -n "$(git rev-list -n1 "refs/remotes/origin/$b..$ref")" ]; then
    diverged="$diverged $b"
  fi
done < <(git for-each-ref --format='%(refname)' refs/remotes/gitlab)
if [ -n "$diverged" ]; then
  say "DIVERGENZ: GitLab hat Commits, die auf GitHub fehlen in:$diverged - nichts gepusht"
  exit 3
fi

fail=0
while read -r ref; do
  b=${ref#refs/remotes/origin/}
  [ "$b" = HEAD ] && continue
  # ccr-* = kurzlebige Sandbox-Branches von Claude Code, gehoeren nicht in den oeffentlichen Spiegel
  case "$b" in ccr-*) continue ;; esac
  if [ "$dry" = 1 ]; then
    say "wuerde pushen: $b"
  elif git push gitlab "$ref:refs/heads/$b" >/dev/null 2>&1; then
    say "Branch $b ok"
  else
    say "FEHLER: Push von Branch $b fehlgeschlagen"; exit 4
  fi
done < <(git for-each-ref --format='%(refname)' refs/remotes/origin)

# Auf GitHub geloeschte Branches auch auf GitLab loeschen (--prune pro Referenz geht nicht).
while read -r ref; do
  b=${ref#refs/remotes/gitlab/}
  [ "$b" = HEAD ] && continue
  git show-ref --verify --quiet "refs/remotes/origin/$b" && continue
  if [ "$dry" = 1 ]; then say "wuerde loeschen: $b"
  elif git push gitlab --delete "$b" >/dev/null 2>&1; then say "Branch $b geloescht"
  else say "FEHLER: Loeschen von Branch $b fehlgeschlagen"; exit 4; fi
done < <(git for-each-ref --format='%(refname)' refs/remotes/gitlab)

if [ "$dry" = 1 ]; then
  say "wuerde Tags pushen"
elif ! git push gitlab --tags >/dev/null 2>&1; then
  say "FEHLER: Push der Tags fehlgeschlagen"; exit 4
fi
say "synchron"
