# Holdfast

Blocks Reddit and Chrome, blocks reddit.com in every other browser, and protects itself from being switched off. Blocking is instant; every unblock waits 24 hours (counted on uptime, so changing the phone clock doesn't skip it).

## Get the APK (GitHub builds it for you)

1. Create a **private** repo on github.com (private matters: the signing key is in it).
2. Upload this whole folder to it (drag and drop on the web works, keep the folder structure, including `.github`).
3. Open the **Actions** tab, wait about 4 minutes for "Build APK" to go green, then download the APK from the run's Artifacts.

Every later push builds a new APK that installs straight over the old one.

## Install on the Galaxy A55 (order matters)

1. Open the APK, allow installs from your browser/Files when asked.
2. Settings > Apps > Holdfast > ⋮ (top right) > **Allow restricted settings**. Needed on Android 13+ for sideloaded apps, or the Accessibility switch stays grey.
3. Open Holdfast > **Activate uninstall protection** > Activate.
4. Open Holdfast > **Open Accessibility settings** > Installed apps > Holdfast > On.
5. Settings > Battery > Background usage limits: make sure Holdfast is **not** in sleeping apps.

Do 3 before 4: once the blocker is on, Settings pages showing "Holdfast" get blocked.

## Updating the rules

- Add apps/sites inside the app: instant.
- Change defaults or behaviour: edit `Rules.java` / `BlockerService.java`, push, install the new APK.

## Known escape hatches

Safe Mode (disables third-party apps), factory reset, ADB from a PC. It raises friction, it doesn't make bypass impossible.

## Riyal Ledger (separate app, installed by Holdfast)

Riyal Ledger is Aaron's budget and savings forecast app. Holdfast blocks every browser and every app installed from a file, so the ledger can't run in a browser or be sideloaded normally. Instead it is built here, next to Holdfast, and Holdfast installs it.

- **Install / update / open:** Holdfast's main screen has a Riyal Ledger section. Holdfast downloads `riyal-ledger.apk` from the latest release and opens the installer, the same way it updates itself.
- **Why it isn't blocked:** `BlockerService.isTrustedOwnApp` trusts exactly one package, `com.aaron.riyalledger`, and only when it is signed with Holdfast's own key. A different app can't borrow the name, and nothing else gets an exception.
- **It can't be used as a browser:** the app runs fully offline from bundled files, blocks every internet request and refuses to open any other page.
- **Source:** the web app lives in `aaronlancemanalo-art/riyal-ledger`. The `ledger/` module here is a thin Android shell; the workflow copies the web app into it on every build (`ledger/src/main/assets/ledger`, not committed). To ship a ledger change: push to riyal-ledger, then run **Build APK** here (Actions > Build APK > Run workflow).
- **Data** lives inside the Riyal Ledger app. Back up from its Settings > Data; Android asks where to save. Uninstalling the ledger app deletes its data.
