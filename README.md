# App Ops Shizuku

**English** · [Deutsch](README.de.md)

App Ops Shizuku controls permissions, App Ops and background behaviour of **other apps** on your Android phone through [Shizuku](https://shizuku.rikka.app/). **No root, no Xposed.** It is meant for people who want to see and restrict what installed apps may do (camera, microphone, contacts, clipboard, background network, …) without rooting the device.

## Requirements

- Android 8.0+ (`minSdk 26`, see `app/build.gradle.kts`)
- A running **Shizuku** server (e.g. started via wireless debugging)
- Shizuku permission granted to App Ops Shizuku

Without Shizuku the app only *shows* state; it changes nothing.

## Install / update

Download the APK from the [latest GitHub release](https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest).

**F-Droid:** the app is prepared for [F-Droid](https://f-droid.org) (build from source, metadata under `fastlane/`, see [docs/fdroid](docs/fdroid/README.md)). F-Droid signs with its own key, so switching between F-Droid and GitHub builds needs a reinstall (export your backup first).

**Two editions** with the same app ID: **full** (as before, incl. Wi-Fi rules, which need location permissions to read the Wi-Fi name) and **lite** (no location permissions at all, no Wi-Fi rules). The GitHub release is *full*.

To get updates automatically, add the repository to [Obtainium](https://github.com/ImranR98/Obtainium) (source: *GitHub Releases*, URL `https://github.com/pikatchu2k3/AppOpsShizuku`). The signing certificate stays the same, so an update installs in place and keeps your data.

## At a glance

App Ops Shizuku is a **privacy control panel for the other apps on your phone**. It can:

- **See and restrict** what each app may do: 49 permissions and App Ops (camera, microphone, location, contacts, calendar, call log, SMS, clipboard, media, background running, overlays, …) — with an **access log** showing when an app last used each one and which attempts were refused.
- **Grant only while needed**: *only while in use* (permission exists only while the app is in front), *only on demand* (app stays closed until you open it) and a *quick lockdown* that takes camera and microphone away from every app at once.
- **Cut network access**: block background data, take an app fully offline, see **per-app traffic** (background shown separately), and switch **private DNS** (with tracker-blocking presets) for the whole phone.
- **Automate**: rules by time or Wi-Fi, a quarantine for newly installed apps, one-tap profile templates, a Quick Settings tile.
- **Find out what apps do**: who wakes the device, who schedules jobs, who reads sensors, who holds accessibility or notification access.
- **Isolate apps**: move them into a work profile (this app can create one itself).
- **Rewrite apps (beta)**: hide from recents, blank thumbnails, spoof device IDs, remove permissions or tracker components, return placeholder data, install parallel clones.
- **Stay in control**: every change is read back from the device, listed in a **change log with undo**, and the app itself has **no internet permission** — nothing leaves the phone.

## Where do I find it?

The app has three areas in a floating bar at the bottom: **Apps**, **Tools** and **Settings**. Tapping an app opens its detail page with the tabs *Permissions*, *Behavior*, *Stealth* and *Network & Trackers*.

| Feature | Where |
|---|---|
| Per-app permissions, access log, recommendations, templates, only while in use | App → *Permissions* |
| Freeze, disable, clear data, standby bucket, battery exemption, only on demand | App → *Behavior* |
| Background data, offline, restrict list, tracker detection | App → *Network & Trackers* |
| Patch, clone, hide, spoof, disable trackers, remove permissions | App → *Stealth* |
| Search, sort, filter (*All / Blocked / Changed*) | Header buttons of *Apps* |
| By permission · Network usage · Activity · Automatic rules · System-wide · Work profile · Change log · Diagnostics | *Tools* |
| Shizuku status, quarantine, system colours, backup, standing access | *Settings* |
| Lockdown | *Tools → System-wide*, and as Quick Settings tile |

## Features

### App list
- Live search (label and package name), sorting by name / install date / update date, filters *All*, *Blocked* (at least one permission set to ignore/deny with this app) and *Changed* (anything this app changed on it).
- A **status line** on every card shows what this app changed on it: blocked permissions, offline, no background data, only on demand, only while in use, quarantine. It is read from the app's own records, so no shell call is needed.
- Gradient cards in the colour of each app icon on a calm star-field background (light and dark). A notice appears while Shizuku is not connected.

### Permissions per app (tab *Permissions*)
- **49 privacy permissions and special accesses** in nine groups — *sensors* (camera, microphone, body sensors, activity recognition, Bluetooth scan, nearby Wi-Fi, local network), *location* (precise, approximate, background, photo location), *data* (contacts, calendar, call log, clipboard, phone state, SMS), *media* (photos, videos, audio, selected media, all files), *accounts*, *background* (run in background, foreground services from background, usage stats), *identity* (device IDs, mock location) *system UI* (overlay, notifications, screen on, picture-in-picture) and *special access* (install unknown apps, system settings, exact alarms, full-screen notifications).
- Each one can be set to default / allow / ignore / deny. Permissions with a runtime grant use `pm grant|revoke` (blocking also sets `user-fixed`, so the app cannot simply ask again); pure App Ops use `cmd appops set`. Ops the device does not know are shown as "not settable", permissions the app never requests as "not requested".
- **Block all** and **Reset all to default** (a reset never grants anything). Profiles are stored and exported as JSON.
- **Access log**: last use, refused attempts and "in use right now" per permission, from a single `cmd appops get`.
- **Recommendation card**: permissions that are granted but unused for 30 days, or unusual for the app's category (games, media, news). Nothing changes until you tap *Revoke*.
- **Profile templates**: *Game*, *Messenger* and *Banking* in one tap; the current profile can be shared as JSON.
- **Only while in use**: an extra mode for permissions Android has no foreground mode for (contacts, calendar, call log, media, accounts, …). Granted while the app is in front, revoked 10 s after leaving or when the screen turns off (Android then ends the app in the background).
- Clipboard note: `deny` can make an app crash on start — *ignore* (silent block) is the better choice, and the app warns about it.

### Across all apps (*Tools*)
- **By permission**: pick one permission and see which apps hold it and when they last used it; switch per app, or revoke it for all user apps at once (system apps are never touched).
- **Lockdown**: takes camera and microphone (optionally location) from all user apps and gives them back to *exactly* those apps. Also available as a **Quick Settings tile**.
- **Quarantine for new apps** (*Settings*, off by default): apps installed while it is on get no background data, no clipboard and no background running until you release them (card *Release* in the app's *Permissions* tab). Apps already in quarantine stay there when you switch the quarantine off.
- **Automatic rules**: by **time** (weekdays + window, also overnight) or **Wi-Fi** (connected / not connected to a network). Action: Lockdown, or take chosen apps offline. The Wi-Fi name is read only after you grant this app the location permission on first use (it has no network access of its own). If Android hides the name while the app runs in the background, the rule is skipped — not switched off — until you open the app. Rules need a running Shizuku.

### Behaviour (tab *Behavior*)
- Disable an app, **freeze** it (`pm suspend`, with your own text in the system dialog), clear its data (confirmed by typing the package name), set the standby bucket, grant or remove the battery (Doze) exemption, "optimize app" (force recompile).
- **Lifecycle profile**: battery exemption + standby bucket + background ops in one step, revertible. Optional **stop app when the screen turns off** (runs a foreground service while enabled).
- **Only on demand:** the app stays closed and locked (`am force-stop` + `pm suspend`, or `pm disable-user`, or **only stop** – `am force-stop` alone, so the app can still be opened from any launcher such as Nova; the watcher notices the start and stops it again after you leave) and only runs after you open it via its own home-screen shortcut. After leaving it, it is locked again after 10 s / 1 min / 5 min, or only when the screen turns off; turning the screen on never releases it. Leaving is detected via usage events (usage access is granted to this app via Shizuku), otherwise via `dumpsys activity`. Opening needs a running Shizuku; with *Disable* the app can also be re-enabled in the system settings without Shizuku. While locked, the app's messages, alarms and background sync do not arrive. Note on *Freeze*: `pm suspend` only blocks opening the app; if the system or another app starts it in the background (job, Bluetooth device, bound service), it runs despite being frozen. The watcher therefore checks every 5 minutes (frozen apps only) and at screen-off whether a frozen app has left the stopped state and force-stops it again; only *Disable* prevents such wake-ups completely. **Own launchers** (e.g. AppDialer NXT) can open a frozen app through the exported action `de.minipc.appopsshizuku.action.OPEN_ON_DEMAND` (extra `pkg`); the caller must use `startActivityForResult` so Android reports who it is, and each new launcher is asked once (*Always allow* / *Only this time* / *Deny*; revocable in the *Behavior* tab).
- **Which apps are allowed:** *only on demand* and *pause when the screen turns off* also work for preinstalled system apps such as Google Maps. Only apps the device needs to work are excluded: System UI, Settings, phone, providers, permission controller, the current launcher and keyboard, Shizuku and this app.
- **Components**: list of the app's launcher entries, activities, receivers (including autostart receivers) and widgets, with *Disable all / Enable all* per section. Android only lets the shell switch single components for apps that hold `CHANGE_COMPONENT_ENABLED_STATE` (mostly system apps) — otherwise the screen says so (see *Honest limits*); use *Disable app* for the icon.
- **When the manufacturer blocks disabling** (e.g. OnePlus/OxygenOS answers `pm disable-user` with *new state: default*): every disable is read back from the device. If it did not take effect, *Can't be disabled? Try other ways …* runs a chain and stops at the first step that works: 1. `pm disable-until-used`, 2. **remove for this user** (`pm uninstall -k --user 0` – APK and data stay; the app shows up as *(removed)* in the list and is restored with `cmd package install-existing`), 3. **hide as device owner** (only if set up, see below), 4. **neutralize** (freeze + force-stop + background ops denied + standby bucket *restricted*). Each step is shown with ✓/✗ and the device's reason. System UI, Settings, phone, providers, permission controller, the current launcher and keyboard, Shizuku and this app are never offered.
- **Device owner (optional, Settings):** `dpm set-device-owner` from the shell lets this app hide apps via `DevicePolicyManager.setApplicationHidden` – a different code path that some manufacturers do not block. Android only allows it when there are **no accounts** and **no second user/work profile** on the device; the app removes nothing itself and shows the device's exact answer. *Give up* unhides every app first, then gives up device owner. The same can be done from a PC: `scripts/device-owner.sh check|set|remove` (adb).

### Network & trackers
- **Per app** (tab *Network & Trackers*): block background data · take the app completely offline · put it on Android's background restrict list · **tracker detection** (reads the manifest and scans the code for known building blocks: Firebase Analytics / DataTransport / Crashlytics, Facebook SDK and Audience Network, AdMob, Google Analytics, Sentry, Mixpanel, Segment, Amplitude, Braze, AppsFlyer, Adjust, Kochava). Each switch is verified by reading the state back; "Reset all" only undoes what this app changed.
- **Network usage** (*Tools*): traffic per app over 7 days, background shown separately. Apps with much background traffic that you rarely open are listed first, with a one-tap "block background".
- **Private DNS** (*Tools → System-wide*): off / automatic / presets (AdGuard, Mullvad adblock, Quad9, Cloudflare) / your own host, with "back to previous".

### Insight (*Tools*)
- **Activity**: which apps wake the device, schedule jobs or read sensors (parsed from `dumpsys`, heuristic).
- **Change log**: every change this app makes, with *Undo* per entry for permission and network toggles.
- **Diagnostics**: harmless checks showing which levers work on *your* device (permission flags, freeze-dialog text, firewall chain, `dumpsys` formats, work profile). The report can be shared as text; it contains model and Android version, no app list and no personal data.
- **Error log** (*Tools → Diagnostics*): records crashes, failed Shizuku commands and internal errors, only on the device (max. ~200 KB, oldest entries drop out, repeats within 10 minutes are written once). After a crash or error a notification offers *Share* and *Save*; after a crash the app also offers to open Diagnostics on the next start. The report contains app version, device model, Android version, the last diagnostics result and the log, including the package names of apps where something failed. Nothing is sent automatically (no internet permission); you decide where it goes. The notification channel *Errors* can be switched off in the system settings.
- **Restart notice**: after a reboot (Shizuku not running yet) a notification says that the configured protection is inactive; once connected, a bar offers to restore settings that drifted.

### System-wide and powerful access (*Tools → System-wide*, *Work profile*)
- **Powerful access** overview: accessibility services, notification access, keyboards, default apps (SMS, phone, assistant, browser), always-on VPN and device admins — revocable where the shell may, otherwise a button into the system settings.
- **Standing access** (*Settings*): the app grants itself `WRITE_SECURE_SETTINGS` once via Shizuku; private DNS and revoking accessibility services then also work after a restart without Shizuku.
- **Work profile**: this app can create a managed profile itself (own profile owner) and move apps into it; it can also move apps into an existing profile (e.g. Shelter) using shell rights alone. Moved apps see neither the contacts nor the files of the main profile and start with their own data.

### Stealth — patch and clone (beta, tab *Stealth*)
- (The controls currently live in the *Components* screen.) Patches an app's APK on the device and re-signs it: hide from recents, hide the launcher icon, blank the recents thumbnail (`FLAG_SECURE`), require an active VPN, hide behind a calculator (vault), app-specific device-ID spoofing (build fields, Android ID, IMEI/IMSI), or install as a **parallel clone**. The vault asks for a PIN (4–10 digits; **default `12345` if left empty** — set your own). Device spoofing has a *random device profile* button; manufacturer, model and fingerprint can also be typed in. *Restore* reinstalls the saved original APK(s). A progress notification shows the current phase (extract, patch code, repack, sign, install, proof, start test) and can be cancelled.
- **Disable trackers**, **remove permissions** (internet, advertising ID, installed-app list, location, phone state, account list) and **placeholder data instead of blocking** (null advertising ID, fixed city-level location) — details and limits under *Work in progress*.

### Settings and backup
- Shizuku status, quarantine switch, *System colors (Material You)*, **standing access**, export / import and the app version.
- **Backup** is one JSON file, saved wherever you choose (the system save dialog suggests `appopsshizuku-backup-<date>.json`, e.g. in *Downloads*): privacy profiles, behaviour settings, lifecycle profiles, automatic rules, "only while in use", the quarantine switch and the lockdown selection. Before anything is applied, an import shows what the file contains and discards invalid entries and disable/freeze requests for protected system apps. Older files stay readable; on import only known permissions are accepted. Live device state (which apps are in quarantine right now) is deliberately not backed up. For scripts and Tasker the import also works without a file dialog: `am start -n de.minipc.appopsshizuku/.MainActivity --es import_file <path>`.

## Safety principles

- **No internet permission**: the app cannot send anything anywhere; its data lives in its own private storage.
- **Nothing is assumed**: every change is read back from the device and reported as done only if it really took effect.
- **Everything is reversible**: change log with *Undo*, "Reset all" undoes only what this app changed, original APKs are saved before a patch.
- **Location only on demand**: the permission is requested the first time you create a Wi-Fi rule, never at start. Saving a Wi-Fi rule also grants this app background location via Shizuku (best effort), because the rule is evaluated in the background; without Wi-Fi rules no location permission is ever held.
- **Open source** under GPL-3.0.

Features marked *beta* or described as new in the release notes have not all been verified on every device — use *Diagnostics* to see which levers work on yours.

## Honest limits

- **Single components of other apps cannot be switched at runtime.** Android 15/16 refuses it to the shell (`SecurityException: Shell cannot change component state`), so there is deliberately no switch for it. The Stealth tab gets around this differently: it edits the manifest of a re-signed copy (see *Work in progress*).
- The network switches stop **network traffic**, not the tracker code inside the app. Ads/analytics that the app starts itself keep running in its process. Only the APK patch in the Stealth tab can remove actual code.
- Tracker detection only knows building blocks that declare manifest components. **"Not found" does not mean "not included."**
- "Completely offline" is not possible for system apps (the switch is disabled).
- *Clear data* is not offered for system apps or this app.
- Some permissions can't be changed on some apps/devices (e.g. an app doesn't request the permission, or the device refuses the op); the app says so instead of pretending.
- **A PC does not give more rights than Shizuku.** adb runs as the same shell user; what the manufacturer refuses to the shell (e.g. `pm uninstall --user` → *only root can delete system app for a particular user*) is refused from a PC too. The only real step up without root is device owner. *Neutralize* is weaker than disabling: the system can still start a frozen app in the background.

## Work in progress (beta)

The Stealth tab (patch, clone, tracker switch-off) is **in development**. It is shown with a permanent notice in the tab, in the confirmation dialog and in the result.

- **In development (beta).** Patching and switching trackers off can damage the app. Back up the app's data before you patch.
- The patched app is signed with **our own key** — **not** with the manufacturer's or Google's key. Therefore: **no more Play updates**, Play Protect may warn, and the way back to the original app is a reinstall (*Restore* reinstalls the saved original APKs; the app's data does not survive that).

**Disable trackers (beta)** — three check boxes (*Ads*, *Usage data & crash reports*, *Install attribution*), plus a line listing the known building blocks found in the app:
1. Components of the chosen groups get `android:enabled="false"` in the patched manifest. Nothing is deleted: the entries and class names stay, so SDK init paths do not break; the framework just no longer instantiates them.
2. For Firebase the officially documented switches are added as the last children of `<application>` (they win over an earlier entry): `firebase_analytics_collection_enabled=false`, `firebase_analytics_collection_deactivated=true`, `firebase_crashlytics_collection_enabled=false` — only if the SDK is detected.
3. There is **no code-level step**: untested smali edits are not allowed.
- Never touched, even if the catalog knows them: push (`FirebaseMessagingService`, third-party `…MessagingService` classes), WorkManager (`androidx.work.*`), billing (`com.android.billingclient.*`), Google/Facebook login, and the DataTransport scheduler (shared infrastructure of push and crash reporting).
- **Proof:** the patched APK is scanned with the built-in catalog (manifest and code scan) and compared with the original: "Building blocks before: 5 — after: 2 — switched off: …".
- **Way back:** the original APK(s) are saved before the patch; the result names the folder ("Original saved: …"). An aborted patch changes nothing on the installed app.
- **Start test:** after installing, the patched app is started once and `logcat` is read for ~15 s (only the app's own processes): `FATAL EXCEPTION`, `ClassNotFoundException`, `NoSuchMethodError`, `VerifyError`, `SecurityException`. A fatal exception is reported as "app crashes after the patch"; other hits are reported as warnings. No silent success. If the app has no launcher activity or cannot be started, the result says the test was **not** done.

**Remove permissions (beta)** — six check boxes, plus a line listing which of these permissions the app declares at all:
- *No internet (offline clone)* `INTERNET` · *No advertising ID* `com.google.android.gms.permission.AD_ID` and the Privacy Sandbox `ACCESS_ADSERVICES_*` permissions · *Cannot see installed apps* `QUERY_ALL_PACKAGES` · *No location* `ACCESS_FINE/COARSE/BACKGROUND_LOCATION` · *No phone state / number* `READ_PHONE_STATE`, `READ_PHONE_NUMBERS` · *No account list* `GET_ACCOUNTS`.
- The `<uses-permission>` entries (including `uses-permission-sdk-23`) are removed from the patched manifest. Unlike *revoke* in the Permissions tab this is a property of the installed APK: it survives restarts, works without a running Shizuku, and the app cannot ask again (`requestPermissions` returns "denied" without a dialog). Without `INTERNET` the app's UID cannot open network sockets at all.
- **Proof:** the manifest of the **signed** APK is compared with the original. If a selected permission is still in it, nothing is installed. Afterwards the same start test as for trackers runs.
- Not promised by the platform below a certain `targetSdk`, and reported as such in the result: zeroed advertising ID only from targetSdk 33 (Google documentation), app-list filtering only from targetSdk 30.
- Without a clone the patched app replaces the original (data loss, see above). The app has to cope with "denied" itself; apps that need the internet stop working without it.
- **Not yet tested on a device** — only unit tests with aapt2 on synthetic and real manifests.

**Placeholder data instead of blocking (beta)** — two switches in device spoofing, only in the patched app or clone:
- *Advertising ID*: `AdvertisingIdClient$Info.getId()` returns a null ID, `isLimitAdTrackingEnabled()` returns `true`.
- *Location*: `LocationManager.getLastKnownLocation(String)` returns a fixed city-level place (default Berlin, 52.52 / 13.405). Useful for apps that crash when location is denied.
- Done by rewriting the call sites in the app's code (smali), the same technique as device-ID spoofing. Not covered: other location APIs (`getCurrentLocation`, Fused Location) and other advertising-ID paths. Empty contacts on purpose are **not** offered (too likely to break apps). Not yet tested on a device.

**Honest limits of the patch**
- The code of switched-off building blocks stays in the APK. Building blocks without manifest components (Segment, Amplitude, old Google Analytics, advertising-ID library, Facebook app events) cannot be switched off this way.
- "Switched off" in the proof means: all switchable manifest components of that building block are disabled. It is a statement about the manifest, not a guarantee that nothing is sent.
- Only the base APK's manifest is edited for the tracker switch-off. A split that contains its own code makes a clone fail with a clear message (the package rename only covers the base APK's code).
- The start test is a log heuristic: a caught `SecurityException` shows up as a warning although the app runs.

**Measured on a real device (Android 14, 3.5 GB RAM, test device — not estimates)**
- Patch without clone (manifest only), 39 MB app: extract → repack → sign → install in about 90 s. This is the fast route.
- Klon/code rewrite (baksmali + smali round trip) is CPU-bound and takes long on weak hardware: a 9 MB app already ran several minutes in `DEX_PATCH` (~2.8 cores of continuous load, ~500 MB RSS); a 37 MB app did not get past `DEX_PATCH 40 %` within nine minutes. On this test device clones are only practical for small apps — the progress notification shows the phase, and cancelling leaves the installed app untouched.
- Observed result on a 37 MB app (8 building blocks found): *before: 8 — after: 6 — switched off: Firebase Analytics, Facebook Audience Network*, plus the three Firebase switches; explicitly still active and reported as such: AdMob, advertising-ID library, Braze, Adjust, DataTransport, Crashlytics. Building blocks the catalog does not know are not covered at all (real case: `com.microsoft.clarity.jobs.UploadSessionJob` stayed active).
- The start test is bounded to 90 s and never blocks the run; if it cannot see the app's process it says "not meaningful" instead of claiming success.

**Clone limits**
- Firebase (`google_app_id`) and AdMob (app ID) are bound to the package name and signature — a clone may report registration errors or get no ads.
- Apps with an integrity check (Play Integrity) may refuse to start.
- The clone starts with **its own data** (no login/history transfer).
- Before patching, the target package name is checked: if another app already uses it, the tab says so and nothing is patched. An earlier clone made by this app is updated in place.

## Report a problem

Open an [issue](https://github.com/pikatchu2k3/AppOpsShizuku/issues/new/choose) with the *Bug report* form. Most helpful is the error report from the app: *Tools → Diagnostics → Error log → Share error report* (or *Save as file* and attach it). Read it before you post it: it contains the device model, Android version and the package names of apps where something failed.

## Build from source

```bash
export ANDROID_HOME=/path/to/android-sdk     # or sdk.dir in local.properties
./gradlew :app:assembleFullDebug             # or :app:assembleLiteDebug
```

JDK 17 or newer is enough (the project targets Java 17); no machine-specific paths are needed. The APK ends up in `app/build/outputs/apk/<edition>/debug/`. Tests: `./gradlew :app:testFullDebugUnitTest :app:testLiteDebugUnitTest`, lint: `:app:lintFullDebug :app:lintLiteDebug`. `./gradlew :app:assembleRelease` builds unsigned release APKs (what F-Droid builds). Optional R8 shrinking: `-Pminify=true` (not verified on a device, off by default). `scripts/release.sh` builds, tests, checks the signature and publishes a GitHub release (maintainer use; reads `ANDROID_HOME`, optional `ARTIFACT_DIR`).

The repository contains **no prebuilt binaries** (only the standard Gradle wrapper). The calculator vault's companion app (`de.minipc.calcyou`, a separate GPL-3.0 project) is no longer bundled; if it is missing after a vault patch, the result tells you to install it separately.

## Design and colours

The app uses a **complete Material 3 palette** derived systematically from a calm petrol/teal seed (light and dark), with red reserved for real errors. *Settings* → **System colors (Material You)**: on (default) = colours from the system wallpaper (Android 12+), off = the app's own petrol palette; the choice is remembered. On Android 11 and older the entry is greyed out. Measured contrast: worst text pair 6.49:1 (light) / 5.46:1 (dark) — target was 4.5:1. The app list and tools use gradient cards on an **animated** star-field background (twinkling stars, a very slow drift, an occasional shooting star; it runs only while the screen is visible, at about 30 frames per second, stops by itself in battery saver or when system animations are off, and can be switched off in *Settings* → *Animated background*); the card colour comes from each app icon and is darkened until white text has at least 4.5:1 contrast (covered by a unit test).

## Signature

GitHub releases are release builds (not debuggable) signed with the debug certificate, SHA-256 `652394f7…7226e` (full value: `652394f75d9ed1ea168561e4056556fd82d3645a6c88020cd30b18c0a3d7226e`). An update can only be installed over an existing install if it has the **same signature**; an APK you build yourself with a different debug key will not update the released app (uninstall first, which deletes app data).

## License

This project is under the **GNU General Public License, version 3.0** — full text in [LICENSE](LICENSE).

The calculator vault works together with a CalcYou fork (GPL-3.0). Since the app is distributed publicly anyway, the whole project uses the same license.

Because the repository is public, the source that belongs to every released package is available in the same place (repository + releases). There is **no warranty**: patching other apps can damage them, and the Stealth tab is used at your own risk.

## Languages

The interface ships in English (base) plus 8 translations — 764 strings each, complete (every string translated, no gaps).

| Language | Locale | Coverage |
|---|---|---|
| German/Deutsch | `values-de` | 764/764 (100%) |
| Spanish/Español | `values-es` | 764/764 (100%) |
| French/Français | `values-fr` | 764/764 (100%) |
| Hindi/हिन्दी | `values-hi` | 764/764 (100%) |
| Japanese/日本語 | `values-ja` | 764/764 (100%) |
| Portuguese (Brazil) | `values-pt-rBR` | 764/764 (100%) |
| Russian/Русский | `values-ru` | 764/764 (100%) |
| Chinese (Simplified)/简体中文 | `values-zh-rCN` | 764/764 (100%) |

All translations are checked by script (placeholders, plural forms, XML structure); `R.array.*` resources are left alone. English is the base, so any device with an unsupported language falls back to English instead of German.
