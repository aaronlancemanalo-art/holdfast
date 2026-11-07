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

## Riyal Ledger (built in)

Holdfast also carries Riyal Ledger, Aaron's budget and savings forecast app, because Holdfast blocks every browser and every app installed from a file, so the ledger can't run on the phone any other way.

- It gets its own **Riyal Ledger** icon in the app drawer and its own entry in recents. There's also an **Open Riyal Ledger** button on Holdfast's main screen.
- The web app is copied from the `aaronlancemanalo-art/riyal-ledger` repo into the APK on every build (`app/src/main/assets/ledger`, not committed). To ship a ledger change to the phone: push to riyal-ledger, then run this repo's **Build APK** workflow (Actions tab > Build APK > Run workflow), and let Holdfast update itself.
- It runs fully offline from the bundled files, refuses to load anything from the internet, and can't navigate anywhere else, so it doesn't reopen a browser route.
- Data lives in the WebView's storage inside Holdfast. Back up from the ledger's Settings > Data; Android asks where to save. Uninstalling Holdfast deletes the ledger data.
