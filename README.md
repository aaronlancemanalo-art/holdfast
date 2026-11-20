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

## What tamper protection covers

While it is on, these close as soon as they open: Holdfast's own Settings pages and uninstall dialog, Developer options, the Safe mode button in the power menu, the two settings-reset pages, Maintenance mode, the app-pinning page, the sleeping-app lists, the power saving options page and the phone language page. If an app is already pinned when something needs blocking, Holdfast locks the screen instead (unpin the app or restart the phone). The checks read Settings in English; in any other phone language every Settings page that mentions Holdfast and every installer screen is closed.

Reddit is recognised by its address, by the titles its pages use and by its links, so it is also closed inside viewers that show no address and under other web addresses. Reddit apps are recognised by name and by being the app that opens Reddit links.

## Reddit

Reddit is blocked in three places at once: the Reddit app on the blocked apps list (it stays there whether or not the app is installed), and `reddit.com` and `redd.it` on the blocked sites list, which also closes other Reddit apps and Reddit under other web addresses. The Reddit section of the app shows whether all three are in place. If one is missing, or a change that would open one is waiting, **Block all of Reddit now** puts everything back in one tap and drops those waiting changes. Other waiting changes are left alone. Build 33 did this once by itself the first time it ran.

A second rule sits underneath, on by default and with the same 24-hour wait to switch off: if Reddit is ever unblocked, it closes when something marked mature is on screen. That means an NSFW or 18+ tag on a post or community, a name with "nsfw" in it, an age gate, or the settings that switch such content on. It reads labels, so it only knows what Reddit marks, and it reacts once the label is on screen. Rules that forbid such content ("No NSFW") and ordinary sentences that use the word are left alone. It is a second layer, not a substitute for the block.

## Testing a change without the phone

`tools/sim/run.sh` runs the real `BlockerService` and `Rules` code on a plain JVM against small stand-in Android classes: fake screens, a fake clock and a fake list of installed apps. `Checks` covers the page and address rules one by one; `Scenarios` plays whole sequences (opening a blocked app, a link tab from Claude, Gemini, Settings pages, the installer, web pages inside apps) and checks what Holdfast did. Add a case there for every rule you change.

    ANDROID_JAR=/path/to/android-34/android.jar tools/sim/run.sh

## Known escape hatches

Safe Mode (disables third-party apps), factory reset, ADB from a PC. It raises friction, it doesn't make bypass impossible.

## Riyal Ledger (separate app, installed by Holdfast)

Riyal Ledger is Aaron's budget and savings forecast app. Holdfast blocks every browser and every app installed from a file, so the ledger can't run in a browser or be sideloaded normally. Instead it is built here, next to Holdfast, and Holdfast installs it.

- **Updates:** Riyal Ledger checks for a new version itself (at most every 6 hours) by comparing its build fingerprint with `ledger-hash.txt` in Holdfast's latest release. The fingerprint only changes when the ledger changes, so Holdfast-only rebuilds don't prompt. Tapping **Update** hands off to Holdfast (`com.aaron.holdfast.action.UPDATE_LEDGER`), which downloads `riyal-ledger.apk` and opens the installer the same way it updates itself. Holdfast shows no ledger buttons, except a one-time "Update Riyal Ledger" for installs older than build 18, which can't check for themselves.
- **Why it isn't blocked:** `BlockerService.isTrustedOwnApp` trusts exactly one package, `com.aaron.riyalledger`, and only when it is signed with Holdfast's own key. A different app can't borrow the name, and nothing else gets an exception.
- **It can't be used as a browser:** the app runs fully offline from bundled files, blocks every internet request and refuses to open any other page.
- **Source:** the web app lives in `aaronlancemanalo-art/riyal-ledger`. The `ledger/` module here is a thin Android shell; the workflow copies the web app into it on every build (`ledger/src/main/assets/ledger`, not committed). To ship a ledger change: push to riyal-ledger, then run **Build APK** here (Actions > Build APK > Run workflow).
- **Data** lives inside the Riyal Ledger app. Back up from its Settings > Data; Android asks where to save. Uninstalling the ledger app deletes its data.
