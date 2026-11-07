DataFlow 2.1 (versionCode 3)        package / applicationId: app.viday        developer: Always ON
=========================================================================================
STATUS (honest): the source was edited and reviewed by hand. It was NOT compiled, signed or run on a
device by the author (no Android SDK or network in the authoring environment). No .apk/.aab is
included, because none could be built here. Build them with the steps below; send any error text back.

WHAT CHANGED IN 2.1
- History: tapping a record opens a bottom sheet (weekday/date, window start/end, big total, progress bar,
  download/upload tiles, limit, % used, status chip, source). It shows the exact record of the tapped card.
  Records longer than 36 h are labelled "Billing cycle window", so a cycle total is not mistaken for a day.
- Settings > About: developer row unchanged ("Always ON"); new GitHub row -> https://github.com/omsn1528
  (opens in the browser; shows a message if no app can open it). It uses a simple code-bracket icon,
  not GitHub's logo.
- Notifications (see below), test-alert button and permission status row.
- Play readiness: targetSdk/compileSdk 36, AGP 8.9.1, Gradle 8.11.1, R8 + resource shrinking, edge-to-edge
  insets, Android 13+ back-callback, restore now transactional, SCHEDULE_EXACT_ALARM removed.
- DB schema unchanged (version 1) -> history and settings are preserved on update.

NOTIFICATION INVESTIGATION (what the code review found; not yet confirmed on a device)
1. Alerts were only evaluated inside the background alarm. While the app was open, nothing checked
   them. FIXED: every refresh now evaluates alerts too (duplicates are blocked per window).
2. Alerts are OFF until "Data alerts" is switched on (Settings > Notifications). Settings now shows
   "Blocked in Android settings" when Android refuses notifications, plus a permission row that opens
   the system page.
3. The 15-minute check could be late; it now checks every 5 minutes when within 10 points of the next
   threshold, 15 minutes otherwise, and stops once all thresholds for the window have fired.
4. Raising the limit or correcting usage now lets thresholds above the new percentage fire again.
5. Alarms are re-armed after reboot, app update, clock and time-zone change.
6. Still possible: Xiaomi/HyperOS can kill alarms if the app is swiped away or battery-restricted. Set
   Battery saver = No restrictions and enable Autostart for DataFlow.
Use Settings > Notifications > "Send test alert" to confirm Android allows notifications, then check
the real 50% alert with a low limit (for example 100 MB) and some mobile browsing.
Only threshold alerts are posted: no status, sync or reminder notifications exist.

BUILD (Android Studio, Windows)
1. Extract into a new folder, File > Open, accept SDK prompts (Android 16 / API 36 platform is needed).
   If asked to upgrade AGP/Gradle, do not; versions are already set.
2. Test build: Build > Build APK(s)  (debug, for your phone only).
3. Release: see SIGNING below, then Build > Generate Signed App Bundle / APK, or run
   gradle bundleRelease assembleRelease. Outputs:
     app\build\outputs\bundle\release\app-release.aab
     app\build\outputs\apk\release\app-release.apk
4. Checksums: certutil -hashfile app-release.aab SHA256   (the GitHub workflow also writes SHA256SUMS).

SIGNING (no key exists in this project; I did not create one without your approval)
- Create ONE permanent upload key (run on your PC, back it up offline in 2 places):
    keytool -genkeypair -v -keystore dataflow-upload.jks -alias dataflow -keyalg RSA -keysize 2048 -validity 10000
- Copy keystore.properties.example to keystore.properties and fill it in, or set the env vars
  DATAFLOW_KEYSTORE_FILE / _PASSWORD / DATAFLOW_KEY_ALIAS / _KEY_PASSWORD.
- If these are missing, the release build is unsigned; it never uses the debug key.
- Get the public fingerprint to share/verify:
    keytool -list -v -keystore dataflow-upload.jks -alias dataflow   (use the SHA-256 line)
- Never put the .jks or passwords in the repo, zip or logs (.gitignore already excludes them).

BUILD WITHOUT A PC (GitHub Actions)
- Put the project in your repository. Settings > Secrets > Actions: KEYSTORE_BASE64
  (base64 of the .jks), KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD.
- Actions > "Build signed release (AAB + APK)" > Run workflow. Download the DataFlow-release artifact
  (AAB, APK, SHA256SUMS; the log prints the public certificate fingerprint).
- The workflow has not been run by the author.

GOOGLE PLAY (manual steps for you)
- Create the app, choose Play App Signing, upload the .aab to Testing > Internal testing, add testers.
- Play requires targetSdk 36 for updates since 2026-08-31 (met). New personal accounts may need a
  closed test with testers before production; check the Console for your account's rules.
- Complete: Data safety (no data collected/shared), privacy-policy URL (PRIVACY.md text), content
  rating, app access. Play may ask you to justify the Usage access permission: core purpose is
  measuring mobile-data usage.
- Not done by the author: nothing has been uploaded or published.

IMPORTANT: UPDATING YOUR CURRENT PHONE APP
Your installed app was signed with a debug key. A release-signed build cannot update it in place;
Android will refuse. To keep history: Settings > Backup (JSON) first, uninstall, install the release
build, Settings > Restore backup. Builds from Play replace sideloaded ones the same way.

PERMISSIONS
Usage access (user-granted), Notifications (asked only when you enable alerts), Boot completed,
Network state. No internet, SMS, contacts, location or accessibility. No exact-alarm permission.

SECURITY REVIEW (static, not penetration-tested)
- Preferences/DB are app-private, hold no secrets; allowBackup=false; cleartext traffic disabled; no network code.
- DB queries are parameterised; restore validates size (2 MB), types and ranges, then applies in one
  transaction; CSV contains only numbers and dates; exported components are limited to the launcher activity.
- No API keys, tokens or credentials anywhere in the project.
- Unverified: runtime behaviour on devices; R8 shrinking (first release build may need a keep rule).

VERSION / DEPENDENCIES
versionName 2.1, versionCode 3, minSdk 26, targetSdk 36, compileSdk 36, Kotlin 1.9.24, AGP 8.9.1,
Gradle 8.11.1, JDK 17, single library: androidx.core:core-ktx 1.13.1.

TEST CHECKLIST (all unrun)
[ ] Debug and release build; AAB generated; signature verified (apksigner verify --print-certs)
[ ] History sheet opens/closes (X, button, outside tap, back); values equal the card; cycle label shown
[ ] GitHub row opens github.com/omsn1528
[ ] Test alert shows; real 50/75/90/100 alerts fire once per window; none while alerts are off
[ ] Raise the limit after an alert: later thresholds can fire again
[ ] Reboot: alarm re-armed; History/settings intact after updating over a build signed with the same key
[ ] Back button on Android 13+/16 returns Home then exits; layout clear of status/navigation bars
