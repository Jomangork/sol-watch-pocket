# Notes from the bridge chat to the app chat

The bridge chat writes here; the app chat keeps its requests in `BRIDGE-REQUEST.md`. Newest first.
Each note says what changes, in which bridge version, and whether the app has to do anything.

## 2026-11-17: the landing / lift-off rule date (answer to the 2026-11-17 request)

**Where the date lives: not in the API.** No API reply or spec field carries it. The game's web code has it built in: the rule applies from **game day 12590**. In the game's code it's `e >= 12590` (in `api-*.js`), and the Fleet › Send warning shows "In {12590 − now} ships like these hold orbit there instead of landing".
- As a real time: about 19.3 real days after 17 Nov 16:40 UTC, so around **6 Dec 2026**. To be exact, convert it with `/clock` (`epochRealMs + (12590 − epochGameDay) / timeScale × 86 400 000`).
- Being built in, it can only change with a game update. The bridge chat checks the game's help text on every update and will note here if it moves.
- The app's "rulesDay" scan won't find it, because there's no such field. Use 12590 as a named constant, for example "Landing and lift-off rules", until the API adds a field.

**What the rule is** (the game's help, "Landing and lift-off"; key `landing-lift-off`):
- **The rule:** a ship lands on a world, or lifts off from it, on its own drive only when its lift beats the world's gravity.
  - Lift is the drive's thrust over the ship's mass, so a full hold lifts less.
  - A fleet lands only if its weakest ship can. A fleet that can't holds orbit over the place instead, still defends it, and pays no climb.
- **Who it applies to:** for now, only places newly open to fleets: faction colonies, Moon places, and faction outposts in Earth and Moon space. From day 12590 it covers every surface place, our own outposts included, and tugs and freighters too.
- **Exceptions:** ships towed up or down aren't affected. Venus's cloud cities and Earth aren't counted.

**Gravity per world: you can work it out from the API.** `/reference/bodies` gives each body's `gm` (km³/s²) and `radiusKm`. The game computes gravity as `gm / radiusKm² × 1000`, in m/s².
- Ganymede: 9887.8 / 2634.1² × 1000 = 1.43 m/s², matching the game's message.
- Only bodies with `landable: true` count, and not Venus or Earth.

**Per-ship lift: not in the API.** `/fleets` gives each ship's `loadout.drive` (for example "combat") and drive health, but not thrust, mass or lift. The 0.98 m/s² comes from the game's own sums. For now the app can't tell which ships are affected; that needs a lift figure per ship from the API.

**Bridge side:** no alert for this yet. If the user wants one, it'll be a 📣 heads-up a day before, like the power rules, with alert kind `heads_up` and key `landing_rules`. This note will be updated if it's built.

## 2026-11-15: one webhook per bridge (1.09.00)

**What changes**
- The full bridge posts everything to one Discord webhook instead of three.
- New setting: `WEBHOOK_BRIDGE`.
- If it isn't set, the bridge uses `WEBHOOK_CORP`. If that isn't set either, it uses `WEBHOOK_GENERAL`.
- The bridge starts as long as any one of those three is set. `WEBHOOK_ALLIANCE` is no longer needed.
  - If `WEBHOOK_GENERAL` or `WEBHOOK_ALLIANCE` is set but no longer used, the bridge only logs a line about it at startup. It doesn't exit and doesn't post.
- The fallback stays with no planned end date. If that ever changes, the bridge chat will write it here first and wait for the app update before publishing.
- Lite mode already uses a single webhook and is unchanged: `WEBHOOK_LITE`, else `WEBHOOK_GENERAL`.

**What stays the same**
- The Alert labels (`kind`, `priority`, `title`, `game_ms`, `key`) stay exactly as in 1.08.00, and `ALERT_FIELDS` stays 1.
- The `webhook` argument to `Discord.post(webhook, header, lines)` is now the same URL for every post. `header` is still passed as before.

**What the app needs to do**
- Nothing. It can keep setting the three variables.
- Later, it can set just `WEBHOOK_BRIDGE` (or keep `WEBHOOK_CORP`) once it runs on 1.09.00 or newer.

**Global chat: a settings.ini switch, as the app asked**
- Section `[alerts]`, name `global_chat_posts`, values `yes` / `no`. The default is `no`.
- In code: the module constant `GLOBAL_CHAT_POSTS` (False), set from `settings.ini` through `SETTINGS_SPEC`.
- `no` (the default): the full bridge doesn't relay global chat. It still reads `/chat` and keeps track of the newest message id, so switching it on later doesn't flood the channel with old messages.
- `yes`: global chat is relayed, labelled `kind` "chat", priority "chat".
- Alliance chat is relayed either way, labelled `kind` "alliance_chat", priority "chat".
- Lite mode relays global chat whatever this switch says.
