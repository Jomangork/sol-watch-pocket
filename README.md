# Sol Watch

Formerly Sol Watch (renamed in 0.8.2; the repository keeps its name so updates keep working).

**Work in progress. Not finished, not fully working, not supported.** Try it only if you're fine with that.

An Android app that runs the [SOL-Watch bridge](https://github.com/Jomangork/sol-watch-bridge) on the phone itself, for the browser space game SOL. It reads the game's read-only API with your own API key and turns the bridge's alerts into phone notifications. There's no Discord, no server and no computer involved: the phone talks to the game, and once a day to GitHub to check for a newer bridge.

It's a player-made tool, not made or supported by the game's developers.

## What it does

- **Alerts as notifications,** all in one bundle. Each one's title is its headline, with the rest underneath and the game time in the header, so nothing shows twice. 🚨 losses and failures, 🚨⚔️ incoming fleets, 🆘 escape capsules and 🔑 key problems are loud. Everything else is silent. Chat has its own channel with a sound.
- **Alert types** (Settings › Alert types, or tap an alert in the list): loud, quiet or off for each kind of alert, such as research finished or a new contract, from the bridge's own list of kinds (bridge 1.08.00+). Off skips the notification but keeps the alert in the history.
- **Alert history** for 30 days (7 to 90, your choice), with search, sorted by topic: All, Urgent, Company, Fleets, Contracts, Sol, Chat and Bridge, each with filters beneath it (Contracts: Board, Alliance jobs, Ship orders, …; Chat: Global, Alliance). A notification's header says where it belongs, such as "Contracts · Alliance jobs".
- **Calendar tab:** the game's coming rule changes (power, relays, ship module parts), Earth's ballot and the Security Council, elevators being built, hazard forecasts, and our own deadlines (research, construction, arrivals, elevator cargo, diplomacy offers). Agenda and month views (construction finishing the same day shares one row), a reminder on any entry, and "Add to phone calendar". It uses only replies the checks already read, so it costs no extra data.
- **Checks** (Settings › Checks). Each of the bridge's checks can run at its own interval or be switched off, with presets (Recommended, Saver, Fast, and Custom for your own mix; the one in use is highlighted). Every check offers the same intervals; Market corruption and Derelicts can't go below 10 minutes, because each read downloads a large reply, and say so under the check. The menu shows reads a minute against the game's limit (orange with ⚠️ from 40, red with ⛔ from 55) and an estimate of the data each check uses. Mobile data can have its own settings, applied when the phone leaves Wi-Fi.
- **My alerts** (Settings › My alerts): your own thresholds on top of the bridge's: money below or above an amount, income falling below a level, a good at a place running out within N hours, crew supplies, research finishing, a good's stock on Earth's market, and a spaceport's wait. Each fires once when its line is crossed, loud or quiet as you choose, and shows its current value. They use the checks' own reads; a spaceport wait adds one small read every 10 minutes.
- **Our fleet flights and elevator cargo** (optional, in Settings › Checks): a notice when one of your fleets leaves, changes course, is about to land and lands, and the same for your space elevator bookings.
- **Home-screen widget:** the latest urgent alert, the next big Calendar event with a countdown, money and income, research, and the next arrivals. It shows more when it's bigger.
- **Report for Claude** (Settings › Status): one text file with status, settings, battery and data use, the errors log and the bridge log, to share or save in Downloads. The API key is never in it; alert and chat text only if you switch it on.
- **Storage** (Settings › Status): the space used, how big the bridge's logs may grow, and how long alert history is kept.
- **Status:** a green, yellow or red dot. Shows which sections your API key can read, plus the bridge's own log. When the game's server is down, the dot turns yellow and the log notes the outage once rather than every failed read.
- **Bridge updates from GitHub,** optional and on by default, checked hourly on Wi-Fi and daily on mobile data. A new `sol_watch.py` is checked before it's used (newer, compiles, still has what the app hooks into), runs on trial, and is rolled back if it fails to start.
- **App updates from this repository's releases,** checked hourly on Wi-Fi and daily on mobile data. The app tells you when a new version is out; tapping the notice downloads it in your browser, and opening the download installs it. The app doesn't install anything by itself: with that ability, Play Protect called it harmful.
- **Sound for every alert,** a switch in Settings › Status, if you want to hear everything, not only urgent alerts and chat.
- **Your company and alliance logos,** from the game's logo share code or an image file.

## Install

1. Download the APK from [Releases](../../releases) and open it on the phone. Allow installing from the app you opened it with.
2. In the game, make an API key just for the phone: Settings › Account › API keys. Tick the same sections as for the bridge: Company, Research, Industry, Launches and craft, Fleets, Mining, Contracts, Discoveries, Alliance, Factions, UEG, Defense, Segments, News and records, and Public chat and Alliance chat if you want chat. Account (daily gift) and Market (Earth's market corruption) are optional.
3. Open the app's **Status** tab, paste the key and tap **Save key**. The list under it shows what the key can read.
4. Tap **Allow background running** so Android doesn't pause the checks while the phone sleeps, then **Start**.

Android 10 or newer, 64-bit ARM phones only.

## Privacy

- **Where data goes:** only between the phone and solthegame.com (the API), plus GitHub once a day for the bridge's version check. No analytics, no other servers.
- **Your API key:** stored on the phone, encrypted with the Android Keystore. It's never in the APK and never sent anywhere but the game.
- **Alerts and logs:** stay in the app's private storage and are excluded from Android's cloud backups.
- **Bridge updates:** whoever can push to the bridge's GitHub repository can change what the app runs. Switch automatic updates off in Settings › Status if you'd rather not.

## Battery and data

The app stays awake to check every minute or so. Measured on one phone over 15 hours in November 2026 (Android's own battery screen and the app's usage log):

- **Battery:** about 0.3% of the battery an hour for the app itself (7% of the phone's drain that day).
- **Data:** about 4.5 MB an hour with the recommended settings (roughly 3 GB a month on Wi-Fi), and about 2.4 MB an hour with the Saver preset on mobile data (roughly 1.7 GB a month). The Checks estimate is lower than this, so treat it as a guide to which checks cost most.

## How it works

The bridge's single file, `sol_watch.py`, runs **unchanged** inside the app through [Chaquopy](https://chaquo.com/chaquopy/) (Python for Android). A small wrapper, `android/app/src/main/python/pocket_runner.py`, hooks in from outside:

- it replaces the Discord sender with Android notifications,
- points the bridge's files at the app's storage,
- passes the key as an environment variable,
- applies the interval or Off chosen for each check,
- and switches off the bridge's developer output.

The rest of the app is Kotlin with Jetpack Compose and a Glance widget.

| Path | What |
| --- | --- |
| `android/` | The app (Gradle project) |
| `android/app/src/main/python/` | The bundled bridge and the wrapper modules (`pocket_*.py`) |
| `tests/` | Python tests for the wrapper (no network) |
| `tools/build.sh` | Builds with a memory cap and low priority (made for a small always-on PC that also runs the bridge) |
| `tools/desktop_run.py` | Runs the wrapped bridge on a computer against the real API, with a test key in `secrets/test-api-key` |
| `tools/make_icon.py`, `tools/icon/` | The app icon, from 24×24 pixel text |

## Building

You need JDK 17, the Android SDK (platform 36, build-tools 36.0.0), Gradle 8.14 and Python 3.13 (Chaquopy's build Python must match the app's Python version). `env.sh` and `tools/build.sh` expect them under `toolchain/` (`toolchain/jdk`, `toolchain/android-sdk`, `toolchain/gradle`); adjust `env.sh` if yours live elsewhere.

```bash
python3 -m unittest discover -s tests                 # wrapper tests
tools/build.sh testReleaseUnitTest assembleRelease    # app tests and a release APK
```

Release builds are signed with the debug key for now, so an APK you build yourself won't install over one from Releases.

## Credits

The bridge (`sol_watch.py`) is from [Jomangork/sol-watch-bridge](https://github.com/Jomangork/sol-watch-bridge), public domain (the Unlicense).
