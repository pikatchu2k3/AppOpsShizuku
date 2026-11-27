# F-Droid

F-Droid baut die App aus dem Quellcode und signiert sie mit **eigenem** Schluessel. Dieses Repository liefert dafuer:

| Was | Wo |
|---|---|
| Beschreibung, Titel, Screenshots, Changelog (`changelogs/<versionCode>.txt`) | `fastlane/metadata/android/<locale>/` |
| Build-Eintrag fuer fdroiddata (Entwurf) | `docs/fdroid/de.minipc.appopsshizuku.yml` |

## Was den Build F-Droid-tauglich macht

- **Keine vorkompilierten Binaerdateien.** Die Vault-Begleit-App (`calcyou.apk`) wird nicht mehr mitgeliefert; `gradle-wrapper.jar` ist der Standard-Wrapper.
- **Portabler Build:** kein fester JDK-Pfad (`gradle.properties`), JDK 17 genuegt, Android-SDK ueber `ANDROID_HOME`/`local.properties`.
- **`dependenciesInfo` aus** (`includeInApk = false`): kein verschluesselter Abhaengigkeits-Block in der APK.
- **Freie Abhaengigkeiten** (Shizuku, AndroidX, Material, apksig, BouncyCastle, smali). `spooftest` (Play-Services-Fixture) wird per `rm:` entfernt; `settings.gradle.kts` bindet es nur ein, wenn der Ordner existiert.
- **Zwei Flavors:** `full` (WLAN-Regeln, Standort-Rechte) und `lite` (keine Standort-Rechte). F-Droid-Entwurf baut `full`; `lite` ist als zweiter Eintrag moeglich (`gradle: [lite]`).
- Release unsigniert: das Signieren mit dem bisherigen Zertifikat ist ein Opt-in (`-PdebugSignedRelease=true`, nur `scripts/release.sh`).

## Wichtig fuer Nutzer

F-Droid-Builds haben einen **anderen Signaturschluessel** als die GitHub-Releases (SHA-256 `652394f7...`). Ein Wechsel zwischen beiden Quellen braucht eine Neuinstallation; dabei gehen App-Daten verloren - vorher die Sicherung (JSON) exportieren. Reproduzierbare Builds, mit denen F-Droid die Upstream-Signatur uebernehmen koennte, sind nicht eingerichtet.

## Lokal pruefen

```bash
./gradlew :app:testFullDebugUnitTest :app:testLiteDebugUnitTest
./gradlew :app:lintFullDebug :app:lintLiteDebug
./gradlew :app:assembleFullRelease          # -> app/build/outputs/apk/full/release/app-full-release-unsigned.apk
# optional (ungetestet auf dem Geraet): R8-Verkleinerung
./gradlew -Pminify=true :app:assembleFullRelease
```

Mit `fdroid build` aus dem fdroiddata-Repository (`fdroid readmeta && fdroid build -v -l de.minipc.appopsshizuku`) laesst sich der Eintrag vor dem Merge Request pruefen.
