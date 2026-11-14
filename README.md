# SOL-Watch Pocket

**Work in progress. Not finished, not fully working, not supported.** Try it only if you're fine with that.

An Android app that runs the [SOL-Watch bridge](https://github.com/Jomangork/sol-watch-bridge) on the phone itself, for the browser space game SOL. It reads the game's read-only API with your own API key and turns the bridge's alerts into phone notifications. There's no Discord, no server and no computer involved: the phone talks to the game, and once a day to GitHub to check for a newer bridge.

It's a player-made tool, not made or supported by the game's developers.

## What it does

- **Alerts as notifications.** 🚨 losses and failures, 🚨⚔️ incoming fleets, 🆘 escape capsules and 🔑 key problems are loud. Everything else is silent, grouped by the bridge's channels (Corp, General, Alliance). Chat has its own channel with a sound.
- **Alert history** for 30 days, with filters and search.
- **Checks menu.** Each of the bridge's checks can run at its own interval or be switched off, with presets (Recommended, Saver, Fast). The menu shows reads a minute against the game's limit and an estimate of the data each check uses. Mobile data can have its own settings, applied when the phone leaves Wi-Fi.
- **Home-screen widget:** the latest urgent alert, money and income, research, and the next arrivals. It shows more when it's bigger.
- **Status:** a green, yellow or red dot. Shows which sections your API key can read, plus the bridge's own log.
- **Bridge updates from GitHub,** optional and on by default, checked hourly on Wi-Fi and daily on mobile data. A new `sol_watch.py` is checked before it's used (newer, compiles, still has what the app hooks into), runs on trial, and is rolled back if it fails to start.
- **App updates from this repository's releases,** checked hourly on Wi-Fi and daily on mobile data, and downloaded only on Wi-Fi. The app installs an update only if it's the same app, newer, and signed with the same key. The first time, Android asks you to allow the app to install updates.
- **Sound for every alert,** a switch on the Status tab, if you want to hear everything, not only urgent alerts and chat.
- **Your company and alliance logos,** from the game's logo share code or an image file.

## Install

1. Download the APK from [Releases](../../releases) and open it on the phone. Allow installing from the app you opened it with.
2. In the game, make an API key just for the phone: Settings › Account › API keys. Tick the same sections as for the bridge: Company, Research, Industry, Launches and craft, Fleets, Mining, Contracts, Discoveries, Alliance, Factions, UEG, Defense, Segments, News and records, and Public chat and Alliance chat if you want chat. Account is optional (daily gift).
3. Open the app's **Status** tab, paste the key and tap **Save key**. The list under it shows what the key can read.
4. Tap **Allow background running** so Android doesn't pause the checks while the phone sleeps, then **Start**.

Android 10 or newer, 64-bit ARM phones only.

## Privacy

- **Where data goes:** only between the phone and solthegame.com (the API), plus GitHub once a day for the bridge's version check. No analytics, no other servers.
- **Your API key:** stored on the phone, encrypted with the Android Keystore. It's never in the APK and never sent anywhere but the game.
- **Alerts and logs:** stay in the app's private storage and are excluded from Android's cloud backups.
- **Bridge updates:** whoever can push to the bridge's GitHub repository can change what the app runs. Switch automatic updates off on the Status tab if you'd rather not.

## Battery and data

The app has to stay awake to check every minute or so, so it uses more battery than a typical app. With the recommended settings it makes about 8 reads a minute. That's roughly 1.4 GB of data a month on mobile data, the worst case, measured with every read finding changes; the Saver preset roughly halves it. The overnight battery figures haven't been measured yet.

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
