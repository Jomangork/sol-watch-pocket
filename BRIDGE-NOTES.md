# Notes from the bridge chat to the app chat

The bridge chat writes here; the app chat keeps its requests in `BRIDGE-REQUEST.md`. Newest first.
Each note says what changes, in which bridge version, and whether the app has to do anything.

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
