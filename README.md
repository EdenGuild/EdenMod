# EdenMod

A client-side Minecraft **1.21.11** Fabric mod that powers the live Discord ↔
in-game guild-chat bridge for the Eden guild. It captures Wynncraft guild chat,
forwards it to the **EdenBot** backend over an authenticated
WebSocket, and renders relayed Discord messages in-game. Account linking uses
Wynncraft v3 OAuth2.

## Build

Requires JDK 21 on your `PATH`.

```bash
./gradlew build        # Windows: gradlew.bat build
```

The installable jar is then at `build/libs/edenmod-*.jar`.

## Usage

1. Press **B** (rebindable in Controls, or open the config from
   [Mod Menu](https://modrinth.com/mod/modmenu) if installed) to open the Eden
   Bridge screen.
2. Click **Link account** (you can also change the backend url here, if needed).
3. Your browser opens the Wynncraft authorization page; approve it. When it says
   "Linked", return to Minecraft.
4. Join Wynncraft. While you are on a `*.wynncraft.com` server and linked, guild
   chat is bridged both ways automatically.

Config persists to `config/edenmod.json`. The bridge only runs on Wynncraft
servers and tears down when you leave; switching Minecraft accounts drops the
session so one account's token cannot be reused by another.

### In-game commands

Run `/eden help` in-game for the full command list.
Highlights include `/eden party` for raid parties and `/eden anni` for Annihilation parties.

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

## Credits

EdenMod's **territory/war suite** (attack timers, in-world territory walls, war
DPS/attendance tracking, clickable shouts, congratulate button) and several
**extra features** (custom crafted-consumable item textures, the radial emote
wheel) are heavily inspired by — and in places ported from —
[AviciaGuild/avomod2](https://github.com/AviciaGuild/avomod2), which is released
under the MIT License. Huge thanks to its authors. Where their code or assets are
reused, the original MIT notice is retained:

```
The MIT License (MIT)

Copyright (c) 2022 AviciaGuild/avomod2 contributors

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in the
Software without restriction, including without limitation the rights to use,
copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

EdenMod as a whole is licensed under the GNU AGPL-3.0 (see `LICENSE.md`); the MIT
components above are included under that license as permitted by the MIT terms.
