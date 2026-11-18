#!/usr/bin/env bash
# App Ops Shizuku als Geraeteinhaber einrichten - vom PC per adb.
#
# Dasselbe kann die App selbst ueber Shizuku (Einstellungen > Geraeteinhaber); adb vom PC hat die
# gleichen Rechte wie Shizuku. Dieses Skript ist fuer den Fall, dass Shizuku nicht laeuft, und
# zeigt vorher an, was Android fuer die Einrichtung verlangt.
#
# Voraussetzungen (Android): keine Konten auf dem Geraet und nur ein Nutzer (keine weiteren
# Nutzer oder Arbeitsprofile). Das Skript ENTFERNT NICHTS - es zeigt nur an, was im Weg steht.
#
# Aufruf:  scripts/device-owner.sh check    # Vorbedingungen pruefen
#          scripts/device-owner.sh set      # einrichten
#          scripts/device-owner.sh remove   # nur ueber die App moeglich (Hinweis)
set -euo pipefail
PKG="de.minipc.appopsshizuku"
ADMIN="$PKG/.ProfileOwnerReceiver"

command -v adb >/dev/null || { echo "adb nicht gefunden (Android Platform-Tools installieren)."; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "Kein Geraet verbunden (USB- oder WLAN-Debugging aktivieren)."; exit 1; }

check() {
  echo "== Nutzer und Profile (es darf nur User 0 geben):"
  adb shell pm list users
  echo
  echo "== Konten (muessen vorher in den Einstellungen entfernt werden):"
  adb shell dumpsys account 2>/dev/null | grep -E "^\s*Account \{" || echo "  keine gefunden"
  echo
  echo "== Ist App Ops Shizuku installiert?"
  adb shell pm path "$PKG" || { echo "  nein"; exit 1; }
}

case "${1:-check}" in
  check)  check ;;
  set)    check; echo; echo "== Einrichten:"; adb shell dpm set-device-owner --user 0 "$ADMIN" ;;
  remove) echo "Abgeben geht nur in der App (Einstellungen > Geraeteinhaber > Abgeben): dabei"
          echo "werden versteckte Apps vorher wieder sichtbar gemacht." ;;
  *)      echo "Aufruf: $0 check|set|remove"; exit 2 ;;
esac
