# Kickoff

Android app that alerts you when your soccer teams kick off, start the second half, and finish
(second-half and full-time alerts stop once you've opened one of that game's alerts). Tapping an
alert shows where the game is on; streaming apps installed on your phone get an "Open" button.

Match data and US broadcasters come from ESPN's public site API. Bolivian league games show Entel Gol
(the exclusive rights holder), and each game links to Live Soccer TV for channels in every country.

## Get the app on your phone

Every push to `main` builds the APK on GitHub Actions and publishes it as the latest release.
On your phone, open:

https://github.com/mhelguero/kickoff/releases/latest/download/Kickoff.apk

Allow your browser to install unknown apps when Android asks. To update, download it again;
it installs over the old version and keeps your teams.

After the first install, open Kickoff and tap **Fix** on the setup banner so alerts arrive on time
(notifications and alarms). Setting Kickoff's battery use to Unrestricted in Android's app settings
is optional, but makes alerts more reliable, especially with power saving on.

## One-time GitHub setup

The workflow signs every build with the same key so updates install over each other. The key is
in `signing/` on the build PC and is never committed. Back up that folder: if it's lost, the next
build has to be installed fresh (uninstall first).

In the repo on GitHub: Settings → Secrets and variables → Actions → New repository secret:

- `KEYSTORE_B64`: contents of `signing/KEYSTORE_B64.txt`
- `KEYSTORE_PASSWORD`: contents of `signing/KEYSTORE_PASSWORD.txt`

Then re-run the workflow (Actions → Build APK → Run workflow).
