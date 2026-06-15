# Eden Bridge — Fabric mod

A client-side Minecraft **1.21.11** Fabric mod that powers the live Discord ↔
in-game guild-chat bridge for the Eden guild. It captures Wynncraft guild chat,
forwards it to the **Eden Bot** backend (its own repository) over an authenticated
WebSocket, and renders relayed Discord messages in-game. Account linking uses
Wynncraft v3 OAuth2.

## Toolchain

Verified against the Fabric meta endpoints (`meta.fabricmc.net`) and Modrinth on
2026-06-10. Pinned in [`gradle.properties`](gradle.properties):

| Component      | Version            |
| -------------- | ------------------ |
| Minecraft      | 1.21.11            |
| Fabric Loom    | 1.17.8             |
| Fabric Loader  | 0.19.3             |
| Fabric API     | 0.141.4+1.21.11    |
| Mappings       | Official Mojang    |
| Java           | 21                 |

> Mojang official mappings are used so the in-game names match the spec exactly
> (`ClientPacketListener#handleSystemChat`). Wynntils is an *optional* dependency
> (used for nickname/rank info if present) — the mod never hard-requires it.

## Build

Requires JDK 21 on your `PATH`.

```bash
./gradlew build        # Windows: gradlew.bat build
```

The installable jar is written to `build/libs/eden-bridge-1.0.0.jar` (ignore the
`-sources` jar).

> The Gradle wrapper (9.5.1, required by Loom 1.17.8) is committed. If the wrapper
> jar is ever missing, regenerate it with a local Gradle:
> `gradle wrapper --gradle-version 9.5.1`.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft
   1.21.11.
2. Put **Fabric API** (`fabric-api-0.141.4+1.21.11.jar`) and
   `eden-bridge-1.0.0.jar` in your `mods/` folder.
3. Launch the 1.21.11 Fabric profile.

## Usage

1. Press **B** (rebindable in Controls, or open the config from
   [Mod Menu](https://modrinth.com/mod/modmenu) if installed) to open the Eden
   Bridge screen.
2. Enter the backend base URL (the `PUBLIC_BASE_URL` of the Eden Bot deployment,
   e.g. `https://eden.example.com`) and click **Link account**.
3. Your browser opens the Wynncraft authorization page; approve it. When it says
   "Linked", return to Minecraft.
4. Join Wynncraft. While you are on a `*.wynncraft.com` server and linked, guild
   chat is bridged both ways automatically. Relayed Discord messages render in
   chat styled to match native guild chat — a guild-emblem shield, a green
   `discord` pill, then `author: message`.

Config persists to `config/eden-bridge.json`. The bridge only runs on Wynncraft
servers and tears down when you leave; switching Minecraft accounts drops the
session so one account's token cannot be reused by another.

### In-game commands

These are available in Minecraft once linked (type them in chat):

| Command | What it does |
| ------- | ------------ |
| `/eden online` | Who is currently connected to the bridge |
| `/eden party` · `/eden party create <raid> [note]` | List or open raid parties (clickable `[JOIN #id]`) |
| `/eden party join <id>` · `/eden party leave [id]` | Join or leave a party |
| `/eden party announce on\|off` | Toggle the in-chat party feed |
| `/eden anni <size> [note]` | Open an Annihilation party (2–10 players) |
| `/eden aspects pending` | Members' pending aspects — **Chiefs only** (reward helper) |
| `/gift <member> <aspect\|emerald\|tome> <amount>` | Gift guild rewards — **Chiefs only** |
| `/dumpemeralds <member>` | Gift all guild-bank emeralds to a member — **Chiefs only** |
| `/eden help` | The in-game command list |

The chief-only `/gift` and `/dumpemeralds` helpers automate the in-game
guild-manage menu to hand out rewards; only use them in line with your guild's and
Wynncraft's rules (see below).

## Wynncraft ToS considerations

This mod reads guild chat that is already shown to the player and relays it to a
Discord channel the guild controls; it does not automate gameplay, send chat
on the player's behalf into the game, or grant any in-game advantage. Even so,
chat relays and client mods touch areas Wynncraft's rules speak to:

- Only run on official Wynncraft servers; the mod gates on `*.wynncraft.com`.
- It does not modify, suppress, or inject in-game chat, and it never auto-sends
  messages into Minecraft — Discord messages are display-only.
- Members opt in by installing the mod and linking their own account; account
  linking is per-user via official Wynncraft OAuth2.
- Review the current [Wynncraft API terms](https://docs.wynncraft.com) and
  in-game rules before deploying to your guild, and disable the bridge if a
  staff member asks.
