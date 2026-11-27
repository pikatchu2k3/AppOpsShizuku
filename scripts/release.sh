#!/usr/bin/env bash
# Release-Kanal fuer AppOpsShizuku: Build -> Tests -> Signatur -> Push -> GitHub-Release -> Gegenpruefung.
# Bricht bei jedem Fehler laut ab. Fortschritt auf stderr, Ergebnis auf stdout.
# Voraussetzung: Versions-Commit liegt auf dem aktuellen Branch (Arbeitsbaum sauber).
set -euo pipefail

REPO="pikatchu2k3/AppOpsShizuku"
EXPECTED_SHA256="652394f75d9ed1ea168561e4056556fd82d3645a6c88020cd30b18c0a3d7226e"
# Pfade aus der Umgebung (kein fester Rechner): ANDROID_HOME, optional JAVA_HOME und ARTIFACT_DIR
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ARTIFACT_DIR="${ARTIFACT_DIR:-$ROOT/build/release-artifacts}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"

export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"

log()  { echo "[release] $*" >&2; }
fail() { echo "[release] FEHLER: $*" >&2; exit 1; }

cd "$(dirname "$0")/.."
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

for tool in git curl python3 md5sum; do
    command -v "$tool" >/dev/null || fail "Werkzeug '$tool' fehlt."
done

# --- Token (nie ausgeben, nie als Argument: Header kommt aus einer 0600-Temp-Datei, die beim Ende geloescht wird) ---
[ -f ~/.git-credentials ] || fail "~/.git-credentials fehlt - kein GitHub-Token verfuegbar."
TOKEN=$(grep -oE 'https://[^:]+:[^@]+@github\.com' ~/.git-credentials | head -1 \
    | sed -E 's#https://[^:]+:([^@]+)@github\.com#\1#' || true)
[ -n "$TOKEN" ] || fail "Kein github.com-Eintrag in ~/.git-credentials gefunden."

(umask 077; printf 'Authorization: Bearer %s' "$TOKEN" > "$TMP/auth")

# api METHOD URL [Datei] -> setzt HTTP_CODE und HTTP_BODY; Datei wird als Body gesendet
api() {
    local method=$1 url=$2 data=${3:-} ctype=${4:-application/json} out
    local args=(-sS -X "$method" -H "@$TMP/auth"
        -H 'Accept: application/vnd.github+json' -w '\n%{http_code}')
    [ -z "$data" ] || args+=(-H "Content-Type: $ctype" --data-binary "@$data")
    out=$(curl "${args[@]}" "$url") || fail "curl fehlgeschlagen: $method $url"
    HTTP_CODE=${out##*$'\n'}
    HTTP_BODY=${out%$'\n'*}
}

# jget 'ausdruck' <<< json  -> Python-Ausdruck auf d (geparstes JSON)
jget() { python3 -c 'import sys,json; d=json.load(sys.stdin); print(eval(sys.argv[1]))' "$1"; }

# --- 1. Version lesen ---
GRADLE=app/build.gradle.kts
VERSION_NAME=$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$GRADLE" | head -1)
VERSION_CODE=$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' "$GRADLE" | head -1)
[ -n "$VERSION_NAME" ] || fail "versionName in $GRADLE nicht gefunden."
[ -n "$VERSION_CODE" ] || fail "versionCode in $GRADLE nicht gefunden."
TAG="v$VERSION_NAME"
log "Version $VERSION_NAME (versionCode $VERSION_CODE), Tag $TAG"

# --- 2. Tag pruefen (kein Suffix-Ausweichen: waere fuer den Update-Kanal eine Prerelease-Falle) ---
[ -z "$(git ls-remote --tags origin "refs/tags/$TAG")" ] \
    || fail "Tag $TAG existiert auf origin schon. Erst versionName/versionCode erhoehen (kein '-2'-Suffix)."

# Arbeitsbaum: Release-Commit muss genau das enthalten, was gebaut wird (Untracked wie APKs ist egal).
git diff --quiet HEAD || fail "Getrackte Dateien sind geaendert/gestagt, aber nicht committet - zuerst committen, sonst fehlt es im Release-Commit."
BRANCH=$(git symbolic-ref --short -q HEAD) || fail "Detached HEAD - bitte auf einem Branch arbeiten."
SHA=$(git rev-parse HEAD)

api GET "https://api.github.com/repos/$REPO"
[ "$HTTP_CODE" = 200 ] || fail "GitHub-Token/Repo-Zugriff fehlgeschlagen (HTTP $HTTP_CODE): $HTTP_BODY"

# --- 3. Build + Tests (Ergebnis aus den XML-Dateien, nicht aus dem Log) ---
rm -rf app/build/test-results/testFullDebugUnitTest
log "Gradle: Tests + assembleFullRelease (mit Debug-Zertifikat signiert, siehe README \"Signatur\") ..."
./gradlew --no-daemon -PdebugSignedRelease=true :app:testFullDebugUnitTest :app:assembleFullRelease --console=plain >&2 \
    || fail "Gradle-Build oder Tests fehlgeschlagen (siehe Log oben)."

shopt -s nullglob
XMLS=(app/build/test-results/testFullDebugUnitTest/*.xml)
[ "${#XMLS[@]}" -gt 0 ] || fail "Keine Test-Ergebnisse unter app/build/test-results/testFullDebugUnitTest/ - Tests nicht gelaufen?"
sum_attr() { grep -h '<testsuite ' "${XMLS[@]}" | grep -oE " $1=\"[0-9]+\"" | grep -oE '[0-9]+' | awk '{s+=$1} END{print s+0}'; }
TESTS=$(sum_attr tests); FAILURES=$(sum_attr failures); ERRORS=$(sum_attr errors)
log "Tests: $TESTS, Failures: $FAILURES, Errors: $ERRORS"
[ "$TESTS" -gt 0 ] || fail "0 Tests gezaehlt - verdaechtig."
[ $((FAILURES + ERRORS)) -eq 0 ] || fail "$FAILURES Failures + $ERRORS Errors in den Unit-Tests."

# --- 4. APK finden ---
APKS=(app/build/outputs/apk/full/release/*.apk)
[ "${#APKS[@]}" -eq 1 ] || fail "Erwartet genau 1 APK unter app/build/outputs/apk/full/release/, gefunden: ${#APKS[@]}."
APK=${APKS[0]}
log "APK: $APK"

# --- 5. Signatur pruefen (hart) ---
APKSIGNER=$(ls "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)
AAPT=$(ls "$SDK"/build-tools/*/aapt 2>/dev/null | sort -V | tail -1 || true)
[ -x "$APKSIGNER" ] || fail "apksigner nicht gefunden unter $SDK/build-tools/*/."
[ -x "$AAPT" ] || fail "aapt nicht gefunden unter $SDK/build-tools/*/."
CERTS=$("$APKSIGNER" verify --print-certs "$APK") || fail "apksigner verify fehlgeschlagen - APK nicht gueltig signiert."
DIGESTS=$(grep -i 'certificate SHA-256 digest' <<<"$CERTS" | awk '{print $NF}' | sort -u)
[ -n "$DIGESTS" ] || fail "Kein Zertifikat-SHA-256 in der apksigner-Ausgabe."
[ "$DIGESTS" = "$EXPECTED_SHA256" ] \
    || fail "Zertifikat-SHA-256 stimmt nicht (erwartet $EXPECTED_SHA256, gefunden: $DIGESTS). Update waere auf dem Handy nicht installierbar."
log "Signatur ok (SHA-256 $EXPECTED_SHA256)"

badging_field() { "$AAPT" dump badging "$1" | grep '^package' | sed -nE "s/.* $2='([^']*)'.*/\1/p"; }
[ "$(badging_field "$APK" versionName)" = "$VERSION_NAME" ] || fail "versionName in der gebauten APK weicht von $VERSION_NAME ab."
[ "$(badging_field "$APK" versionCode)" = "$VERSION_CODE" ] || fail "versionCode in der gebauten APK weicht von $VERSION_CODE ab."

# --- 6. Artefakt kopieren + MD5 ---
APK_NAME="AppOpsShizuku-$TAG.apk"
mkdir -p "$ARTIFACT_DIR" || fail "Ordner $ARTIFACT_DIR nicht anlegbar."
cp "$APK" "$ARTIFACT_DIR/$APK_NAME" || fail "Kopieren nach $ARTIFACT_DIR fehlgeschlagen."
MD5=$(md5sum "$ARTIFACT_DIR/$APK_NAME" | awk '{print $1}')
SIZE=$(stat -c %s "$ARTIFACT_DIR/$APK_NAME")
log "Artefakt: $ARTIFACT_DIR/$APK_NAME ($SIZE Bytes, MD5 $MD5)"

# --- 7. Push (kein --force) + Beleg ---
log "Push: $BRANCH -> origin"
git push origin "$BRANCH" >&2 || fail "git push fehlgeschlagen (kein Force-Push versucht)."
REMOTE_SHA=$(git ls-remote origin "refs/heads/$BRANCH" | cut -f1)
[ "$REMOTE_SHA" = "$SHA" ] || fail "Push nicht belegt: origin/$BRANCH = '${REMOTE_SHA:-leer}', lokal HEAD = $SHA."
log "Push belegt: origin/$BRANCH = $SHA"

# --- 8. Release anlegen ---
if [ -f release-notes.md ]; then
    grep -qF "$VERSION_NAME" release-notes.md \
        || fail "release-notes.md erwaehnt $VERSION_NAME nicht - veraltet? Aktualisieren oder Datei entfernen."
    cp release-notes.md "$TMP/body.md"
    log "Release-Text aus release-notes.md"
else
    {
        echo "App Ops Shizuku $VERSION_NAME (versionCode $VERSION_CODE)"
        echo
        git log -1 --format=%s
        echo
        echo "Download: https://github.com/$REPO/releases/latest"
    } > "$TMP/body.md"
    log "release-notes.md fehlt - Text aus Version + Commit-Betreff"
fi
python3 - "$TAG" "$SHA" "App Ops Shizuku $VERSION_NAME" "$TMP/body.md" > "$TMP/release.json" <<'PY'
import json, sys
tag, sha, name, body = sys.argv[1:5]
print(json.dumps({"tag_name": tag, "target_commitish": sha, "name": name,
                  "body": open(body, encoding="utf-8").read(),
                  "draft": False, "prerelease": False}))
PY
api POST "https://api.github.com/repos/$REPO/releases" "$TMP/release.json"
[ "$HTTP_CODE" = 201 ] || fail "Release anlegen fehlgeschlagen (HTTP $HTTP_CODE): $HTTP_BODY"
REL_ID=$(jget 'd["id"]' <<<"$HTTP_BODY")
REL_URL=$(jget 'd["html_url"]' <<<"$HTTP_BODY")
log "Release $REL_ID angelegt: $REL_URL"

# --- 9. Asset hochladen ---
api POST "https://uploads.github.com/repos/$REPO/releases/$REL_ID/assets?name=$APK_NAME" \
    "$ARTIFACT_DIR/$APK_NAME" application/octet-stream
[ "$HTTP_CODE" = 201 ] || fail "Asset-Upload fehlgeschlagen (HTTP $HTTP_CODE): $HTTP_BODY
Release $REL_ID ($REL_URL) ist OHNE APK oeffentlich - von Hand bereinigen (Release + Tag $TAG loeschen) oder Asset nachladen."

# --- 10. Gegenpruefen ---
api GET "https://api.github.com/repos/$REPO/releases/tags/$TAG"
[ "$HTTP_CODE" = 200 ] || fail "Release zu $TAG nicht abrufbar (HTTP $HTTP_CODE)."
CHECK=$(printf '%s' "$HTTP_BODY" | python3 -c '
import sys, json
d = json.load(sys.stdin); name = sys.argv[1]
a = next((x for x in d["assets"] if x["name"] == name), None)
if d["draft"] or d["prerelease"]: print("draft/prerelease gesetzt"); sys.exit(0)
if a is None: print("Asset fehlt"); sys.exit(0)
if a["state"] != "uploaded" or a["size"] <= 0: print("Asset state=%s size=%s" % (a["state"], a["size"])); sys.exit(0)
print("OK %s %s" % (a["size"], a["browser_download_url"]))
' "$APK_NAME")
[[ "$CHECK" == OK\ * ]] || fail "Gegenpruefung Release: $CHECK"
ASSET_SIZE=$(cut -d' ' -f2 <<<"$CHECK"); ASSET_URL=$(cut -d' ' -f3 <<<"$CHECK")
[ "$ASSET_SIZE" = "$SIZE" ] || fail "Asset-Groesse auf GitHub ($ASSET_SIZE) != lokal ($SIZE)."

# Herunterladen wie der Update-Kanal. Achtung: bei einem PRIVATEN Repo liefert die
# oeffentliche Download-URL einem Fremden 404 (9 Bytes "Not Found") - dann ueber die
# API mit Token pruefen und klar sagen, dass der Update-Kanal einen Token braucht.
api GET "https://api.github.com/repos/$REPO"
IS_PRIVATE=$(printf '%s' "$HTTP_BODY" | jget 'd["private"]')
DL="$TMP/$APK_NAME"
DL_OK=0
if [ "$IS_PRIVATE" = "False" ]; then
    for i in 1 2 3 4 5 6; do
        CODE=$(curl -sSL -o "$DL" -w '%{http_code}' "$ASSET_URL" 2>/dev/null || true)
        if [ "$CODE" = "200" ] && [ "$(stat -c %s "$DL" 2>/dev/null || echo 0)" = "$SIZE" ] \
            && [ "$(md5sum "$DL" | awk '{print $1}')" = "$MD5" ]; then
            DL_OK=1; break
        fi
        log "Download-Versuch $i: HTTP ${CODE:-?} (GitHub braucht nach dem Oeffentlichstellen kurz Zeit)"
        sleep 10
    done
    [ "$DL_OK" = "1" ] || fail "Download-Pruefung fehlgeschlagen ($ASSET_URL). Release $REL_ID ($REL_URL) steht, aber das Asset ist anonym nicht sauber abrufbar."
    log "Anonymer Download ok (wie Obtainium)"
else
    ASSET_ID=$(api GET "https://api.github.com/repos/$REPO/releases/tags/$TAG"; printf '%s' "$HTTP_BODY" \
        | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["assets"][0]["id"])')
    curl -sSL -H "@$TMP/auth" -H 'Accept: application/octet-stream' -o "$DL" \
        "https://api.github.com/repos/$REPO/releases/assets/$ASSET_ID" \
        || fail "Download ueber die API fehlgeschlagen ($ASSET_URL)."
    [ "$(md5sum "$DL" | awk '{print $1}')" = "$MD5" ] \
        || fail "Download-Pruefung (mit Token) fehlgeschlagen: MD5 != $MD5."
    log "Download mit Token ok - HINWEIS: das Repo ist privat, der Update-Kanal braucht dort einen Token."
fi
[ "$(stat -c %s "$DL")" = "$SIZE" ] || fail "Groesse des heruntergeladenen Assets != $SIZE."
[ "$(badging_field "$DL" versionName)" = "$VERSION_NAME" ] \
    || fail "versionName des heruntergeladenen Assets != $VERSION_NAME - Update-Kanal wuerde es nicht anbieten."
log "Gegenpruefung ok: heruntergeladenes Asset hat MD5 $MD5 und versionName $VERSION_NAME"

# --- 11. Ergebnis (stdout) ---
cat <<EOF
Release OK
Tag:     $TAG
URL:     $REL_URL
APK:     $APK_NAME
Groesse: $SIZE Bytes
MD5:     $MD5
Tests:   $TESTS (Failures $FAILURES, Errors $ERRORS)
EOF
