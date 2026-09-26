<div align="center">

<img src=".github/assets/icon-nbg.png" alt="NexoSync" width="180">

# NexoSync

### One server edits. Every server updates.

**Keep your Nexo items, glyphs and resource packs identical across an entire network — using nothing but a GitHub repository.**

![Paper](https://img.shields.io/badge/Paper-1.21+-0288D1?style=for-the-badge)
![Folia](https://img.shields.io/badge/Folia-supported-7E57C2?style=for-the-badge)
![Java](https://img.shields.io/badge/Java-21+-E76F00?style=for-the-badge)
![Proxy](https://img.shields.io/badge/proxy-not%20required-4CAF50?style=for-the-badge)
![License](https://img.shields.io/badge/license-MIT-455A64?style=for-the-badge)

*by **Sorvia Development Solutions** · **hugefiz-dev***

</div>

---

## The problem

You added a new custom sword. Now you open an SFTP client, copy `items/` to the lobby, copy it to
survival, copy it to skyblock, forget which one you already did, reload three times, and discover
tomorrow that skyblock is still running last week's glyphs.

## The fix

```
          ✏️  You edit Nexo on one server
                       │
                  /nexosync push
                       │
                       ▼
              📦  GitHub Release
                       │
        ┌──────────────┼──────────────┐
        ▼              ▼              ▼
      Lobby         Survival       SkyBlock
    ✅ updated     ✅ updated     ✅ updated
```

Every other server notices the new release on its own, downloads it, checks every file against a
SHA-256 manifest, backs up what it currently has, swaps in the new content, reloads Nexo, and tells
your Discord how it went. If anything goes wrong, it puts the old version back.

No proxy plugin. No database. No server-to-server connection. **Just GitHub.**

---

## See it in action

<div align="center">

[<img src="https://img.youtube.com/vi/XHST7RUHpL4/maxresdefault.jpg" alt="NexoSync demo video" width="640">](https://www.youtube.com/watch?v=XHST7RUHpL4)

*A publisher pushes a snapshot and a receiver picks it up — the whole loop, start to finish.*

</div>

---

## Table of contents

- [See it in action](#see-it-in-action)
- [Features](#features)
- [Requirements](#requirements)
- [Quick start](#quick-start-5-minutes)
- [What gets synchronized](#what-gets-synchronized)
- [Commands](#commands)
- [Configuration essentials](#configuration-essentials)
- [Safety: what happens when things break](#safety-what-happens-when-things-break)
- [Discord notifications](#discord-notifications)
- [Troubleshooting](#troubleshooting)
- [FAQ](#faq)
- [Building from source](#building-from-source)

---

## Features

| | |
|---|---|
| 📦 **Snapshot based** | Every release is a complete, immutable copy of your Nexo content — not a patch |
| 🔄 **Automatic** | Receivers check GitHub on an interval and install new snapshots themselves |
| 🔐 **Verified** | SHA-256 for every single file, plus a whole-snapshot hash, checked before anything is touched |
| 💾 **Backed up** | A backup is taken *before* the first live file is deleted — always |
| ↩️ **Self-healing** | A failed reload rolls back automatically; a crash mid-update is detected and repaired on restart |
| 🧹 **Truly in sync** | Files you delete on the publisher get deleted everywhere — no stale leftovers |
| 🚫 **Never destructive** | A network error, bad download or checksum mismatch changes *nothing* on your server |
| 🧵 **Folia ready** | All I/O is off-thread; the only server-thread work is the Nexo reload itself |
| 🔔 **Discord reports** | Stage-by-stage success and failure embeds, with secrets stripped |
| 🌍 **English & Turkish** | Both bundled; every message lives in an editable language file |
| 🔑 **Least privilege** | Publishers need write access; receivers only need read |

---

## Requirements

- **Paper or Folia 1.21+** (Spigot and CraftBukkit are not supported)
- **Nexo**, installed and working — tested against **Nexo 1.28**
- **Java 21** or newer
- A **GitHub repository** to hold your snapshots — private is fine, but it needs at least one commit
- A **GitHub token** ([see below](#3-create-a-github-token))
- *(optional)* A Discord webhook URL

> NexoSync is a normal Paper/Folia plugin. **Do not** install it on Velocity or BungeeCord.

---

## Quick start (5 minutes)

### 1. Install

Drop `NexoSync-1.0.0.jar` into `plugins/` and start the server. NexoSync creates:

```
plugins/NexoSync/
├── config.yml
├── lang/
│   ├── en_US.yml
│   └── tr_TR.yml
├── backups/
├── cache/
└── logs/
```

Then **stop the server** and configure it.

### 2. Create the snapshot repository

Make a GitHub repository — for example `yourname/network-nexo`. NexoSync only uses its
**Releases**, never its files.

> ⚠️ **Tick "Add a README file" when you create it**, or add any file afterwards. GitHub cannot
> create a release in a repository that has no commits at all, and `/nexosync push` will fail with
> *"Repository is empty"*. One file is enough — NexoSync never reads it.

### 3. Create a GitHub token

Use a **fine-grained personal access token** limited to that single repository:

| Server role | Repository access | Permission |
|---|---|---|
| **Publisher** (can run `/nexosync push`) | Only your snapshot repository | **Contents: Read and write** |
| **Receiver** (only installs updates) | Only your snapshot repository | **Contents: Read** |

> ⚠️ Never give the token access to unrelated repositories, and never paste it into Discord, a
> support ticket or a public config.

### 4. Provide the token as an environment variable

NexoSync resolves `${NAME}` from your environment, so the token never has to be written into
`config.yml`:

```bash
NEXOSYNC_GITHUB_TOKEN=github_pat_xxxxxxxxxxxx
```

<details>
<summary>How do I set an environment variable?</summary>

**Linux / start script** — add it above your start command:
```bash
export NEXOSYNC_GITHUB_TOKEN="github_pat_xxxxxxxxxxxx"
java -jar paper.jar --nogui
```

**Windows `.bat`:**
```bat
set NEXOSYNC_GITHUB_TOKEN=github_pat_xxxxxxxxxxxx
java -jar paper.jar --nogui
```

**Pterodactyl / most panels** — add it under *Startup → Variables*.

**No environment variables available?** You can paste the token straight into `config.yml`
instead — just make sure that file never ends up in a public repository or a support screenshot.
</details>

### 5. Configure

Open `plugins/NexoSync/config.yml` and set three things:

```yaml
github:
  owner: "yourname"                 # ← your GitHub username or organization
  repository: "network-nexo"        # ← your snapshot repository
  token: "${NEXOSYNC_GITHUB_TOKEN}" # ← leave as-is if you used step 4

sync:
  mode: "publisher"                 # ← "publisher" on ONE server, "receiver" on the rest
```

A typical network:

| Server | `sync.mode` | Token permission |
|---|---|---|
| Lobby | `publisher` | Contents: Read **and write** |
| Survival | `receiver` | Contents: Read |
| SkyBlock | `receiver` | Contents: Read |

> `both` also exists and lets a server publish *and* receive. Use it only if you know you want it.

### 6. Publish your first snapshot

Start the publisher server and run:

```
/nexosync push
```

That's it. Your receivers will pick it up within their check interval (60 seconds by default).

On the publisher:

```
[NexoSync] [bc38ab1f] Published snapshot v1 - 18 files, 1.24 MB, 3.20s (18 added, 0 modified, 0 removed).
```

And on each receiver, a minute later:

```
[NexoSync] [6d7f3e81] Updated none -> v1 in 3.41s (18 added, 0 modified, 0 removed).
```

---

## What gets synchronized

By default, exactly these three directories inside `plugins/Nexo/`:

```yaml
sync:
  paths:
    - "glyphs"
    - "items"
    - "pack/external_packs"
```

### ✅ Synchronized

| Path | What it holds |
|---|---|
| `Nexo/glyphs/` | Your glyph definitions |
| `Nexo/items/` | Your custom item definitions |
| `Nexo/pack/external_packs/` | External packs Nexo merges into the generated resource pack |

### ❌ Never touched

Everything else stays local to each server — `settings.yml`, `mechanics.yml`, `recipes.yml`,
`config.yml`, `data/`, `logs/`, and anything else you have not listed. That is deliberate: your
lobby and your survival server should keep their own settings.

NexoSync also does **not** ship Nexo's generated `pack/assets` output. It syncs the *source* content
and lets each server's Nexo regenerate its own resource pack. Cleaner, and much smaller downloads.

### The one rule that surprises people

> ⚠️ **A synchronized directory is replaced, not merged.**

```
Before update            Snapshot contains        After update
Nexo/items/              items/                   Nexo/items/
├── ruby.yml             ├── ruby.yml             ├── ruby.yml
├── sword.yml            └── sword.yml            └── sword.yml
└── old_item.yml                                  ← old_item.yml is gone
```

That is the entire point — it is how a deletion on the publisher reaches every server. But it also
means **anything you add by hand inside a synchronized directory on a receiver will be wiped** on
the next update. Put server-specific content outside `sync.paths`.

---

## Commands

All commands require the permission **`nexosync.admin`** (default: operators). Aliases: `/nsync`, `/nxs`.

| Command | What it does |
|---|---|
| `/nexosync` | Show the command list |
| `/nexosync push` | Publish the current Nexo content as a new snapshot |
| `/nexosync check` | Ask GitHub whether a newer snapshot exists — **changes nothing** |
| `/nexosync update` | Download and install the latest snapshot right now |
| `/nexosync status` | Installed version, latest version, GitHub connection, Nexo, next check |
| `/nexosync rollback` | Restore the most recent backup |
| `/nexosync rollback <version>` | Restore a specific snapshot version |
| `/nexosync backups` | List every stored backup |
| `/nexosync reload` | Reload NexoSync's own config and language file |
| `/nexosync version` | Version information |

```
/nexosync status

NexoSync Status
Mode: RECEIVER
Installed snapshot: v42
Latest snapshot: v42
State: UP TO DATE
Next check: 37s
Platform: Folia
Nexo: 1.10.0
GitHub: connected (yourname/network-nexo, read)
```

---

## Configuration essentials

`config.yml` is fully commented. These are the settings most people actually change.

### How often to check

```yaml
update:
  check-interval-seconds: 60   # a check is metadata only — it is cheap
  auto-update: true            # false = notify only, install manually with /nexosync update
```

> A check does **not** download anything. The snapshot archive is only fetched once a genuinely
> newer release exists, so a 60-second interval is completely fine.

### Review updates before they apply

Want to see what is coming before it lands on production? Set `auto-update: false`. NexoSync will
still check and still tell you (console + Discord), but will wait for `/nexosync update`.

### Backups

```yaml
backup:
  enabled: true      # do not turn this off on production
  keep-last: 5       # how many backups to retain
  compress: true     # store as .zip instead of a folder
```

### Which directories to sync

```yaml
sync:
  paths:
    - "glyphs"
    - "items"
    - "pack/external_packs"
```

Add more if you want, but remember the [replace rule](#the-one-rule-that-surprises-people) — and
make sure **every** server in the network uses the same list.

### Language

NexoSync ships with **English** and **Turkish**. Both files are written to
`plugins/NexoSync/lang/` on first start, so you can read and edit either one.

```yaml
plugin:
  language: "en_US"   # or "tr_TR"
```

Then run `/nexosync reload`.

<details>
<summary>Making your own translation</summary>

Copy `lang/en_US.yml` to `lang/<your-code>.yml`, translate the values, and point `plugin.language`
at it. Two things to keep:

- the `{placeholder}` tokens — `{version}`, `{reason}`, `{stage}` and friends are filled in by the
  plugin, so a message that loses one loses that information
- the `<color>` tags — these are [MiniMessage](https://docs.advntr.dev/minimessage/format.html)
  formatting

A key you delete or misspell simply falls back to English, so a half-finished translation never
leaves a blank message on screen.

</details>

> Console output, the log file and Discord embeds are always English — those are read while
> debugging, often by someone who did not set the server's language.

### Console output

The console reports **outcomes**, not steps:

```
[NexoSync] NexoSync 1.0.0 by Sorvia Development Solutions | Paper 1.21.4 | Nexo 1.10.0 | mode RECEIVER
[NexoSync] Synchronizing glyphs, items, pack/external_packs in plugins/Nexo
[NexoSync] Checking GitHub for new snapshots every 60 seconds
[NexoSync] [6d7f3e81] Updated v41 -> v42 in 4.82s (3 added, 14 modified, 2 removed).
```

That is the whole output of a normal update. A sixty-second check that finds nothing prints nothing
at all.

Every individual step — scanning, downloading, verifying, backing up, installing, reloading — is
still written to `plugins/NexoSync/logs/nexosync.log` with the same operation ID, so the detail is
there when you need it. To mirror that detail to the console while setting things up:

```yaml
plugin:
  debug: true
```

### Safety limits

```yaml
safety:
  max-package-size-mb: 512
  max-file-count: 10000
  reject-symbolic-links: true
  reject-path-traversal: true
  strict-managed-paths: true   # refuse snapshots containing anything outside your sync paths
```

Leave these alone unless you have a genuinely huge pack.

---

## Safety: what happens when things break

NexoSync only ever deletes live files **after** the replacement has been downloaded, verified *and*
backed up. Here is what each failure actually costs you:

| What goes wrong | What happens to your server |
|---|---|
| GitHub is unreachable | ✅ Nothing. Retries later. |
| Token is invalid or expired | ✅ Nothing. Logged, and reported to Discord. |
| Download fails or is interrupted | ✅ Nothing. The partial file is discarded. |
| ZIP is corrupt | ✅ Nothing. Update aborted before staging. |
| A file's checksum does not match | ✅ Nothing. Update aborted. |
| The archive tries a path traversal | ✅ Nothing. Rejected outright. |
| The backup cannot be created | ✅ Nothing. **The update refuses to proceed.** |
| Nexo fails to reload the new content | ↩️ Automatic rollback to the previous snapshot, then reload |
| Server crashes mid-update | 🔍 On restart NexoSync verifies the managed directories and either confirms the update, confirms nothing changed, or restores a backup |
| Rollback itself fails | 🛑 NexoSync **stops touching anything**, preserves the backup, and raises a critical Discord alert |

### Rolling back manually

```
/nexosync backups          → see what is available
/nexosync rollback         → restore the most recent backup
/nexosync rollback 41      → restore the backup taken while v41 was installed
```

### Every operation has an ID

```
[NexoSync] [6d7f3e81] Starting update to v42.
[NexoSync] [6d7f3e81] Backup v41-20260918-143002 created (18 files, 1.21 MB).
[NexoSync] [6d7f3e81] Update to v42 failed at stage NEXO RELOAD: ...
```

Grep that ID in `plugins/NexoSync/logs/nexosync.log` to see one operation end to end.

---

## Discord notifications

```yaml
discord:
  enabled: true
  webhook-url: "${NEXOSYNC_DISCORD_WEBHOOK}"
```

You get an embed like this on every server that updates:

```
✅ Nexo Snapshot Updated

Server            Previous Snapshot     New Snapshot
Survival          v41                   v42

Files             Duration              Integrity
18                4.82s                 SUCCESS

Changes
3 added, 14 modified, 2 removed
+ items/emerald_axe.yml
~ items/ruby_sword.yml
- items/old_item.yml
```

And when something fails, you get the **exact stage** it failed at, plus whether the rollback
succeeded — not a generic "update failed".

Turn individual events on and off under `discord.events`. By default you are notified about
outcomes (success, failure, rollback) and not about routine progress.

> 🔒 Tokens, webhook URLs and authorization headers are stripped from every message, log line and
> error report — including ones that appear inside third-party stack traces.

---

## Troubleshooting

<details>
<summary><b>No update is detected</b></summary>

Run `/nexosync status` first, then check:

- `github.owner` and `github.repository` are correct and no longer the placeholder values
- the token is set and has at least **Contents: Read**
- a release actually exists, and it is **published** — not a draft or prerelease
- its tag matches `nexosync-v<number>` (e.g. `nexosync-v42`)
- the release has the `nexo-snapshot-v<number>.zip` asset attached
- the remote version is **higher** than the installed one
- `update.enabled` is `true` and `sync.mode` is `receiver` or `both`
</details>

<details>
<summary><b>Push fails</b></summary>

- The token needs **Contents: Read and write** — read-only is not enough to create a release
- `sync.mode` must be `publisher` or `both`
- If the tag already exists, NexoSync refuses rather than overwriting a published release. Delete
  that release on GitHub or let the next version number be used.
- Check the console for the failing stage: `CONFIGURATION`, `GITHUB_AUTH` and `GITHUB_API` mean
  very different things.
</details>

<details>
<summary><b>"No changes detected"</b></summary>

Working as intended. Your local content is byte-for-byte identical to the latest release, so
NexoSync will not create a duplicate release. Change something in a synchronized directory first.
</details>

<details>
<summary><b>The update downloads but does not install</b></summary>

Look for these in the console:

- `MANIFEST` — the archive's `manifest.json` is missing or malformed
- `INTEGRITY` — a checksum did not match, or the archive contained an undeclared file
- `INTEGRITY` + *"outside this server's configured sync paths"* — the publisher syncs more
  directories than this server does. Make `sync.paths` identical everywhere.
- `BACKUP` — NexoSync could not write to the backup directory. Check disk space and permissions.
</details>

<details>
<summary><b>Nexo breaks after an update</b></summary>

If `rollback.rollback-on-reload-failure` is on (the default), the previous snapshot has already been
restored — confirm with `/nexosync status`. Then check the Nexo log for the definition it refused to
load, fix it on the publisher, and push again.

If NexoSync reported success but Nexo still looks wrong, reload verification is best effort (see
below). Use `/nexosync rollback` and investigate on the publisher.
</details>

<details>
<summary><b>The Nexo reload takes too long</b></summary>

```yaml
nexo:
  reload:
    timeout-seconds: 180
```

Large packs genuinely take a while to regenerate. Do not lower this to hide slow reloads — a
timeout is treated as a failure and triggers a rollback.
</details>

<details>
<summary><b>NexoSync will not enable at all</b></summary>

Two checks run at startup and will stop the plugin deliberately:

- `nexo.required: true` and Nexo is not installed
- `sync.require-nexo-directory: true` and `plugins/Nexo` does not exist

Install Nexo and start once so it creates its directory, or turn those switches off.
</details>

---

## FAQ

**Do I need Velocity, BungeeCord or a proxy plugin?**
No. Every server talks to GitHub independently. They never talk to each other.

**Does the repository have to be private?**
No, but remember that release assets on a public repository are publicly downloadable. If your Nexo
content is proprietary, use a private repository. NexoSync does not encrypt resource packs.

**Can two servers publish?**
Yes — any server with a write token can. NexoSync has no "master" server. Just avoid pushing from
two servers at the same time, and pick the higher version if you do.

**What is the difference between "NexoSync 1.0.0" and "snapshot v42"?**
`1.0.0` is the plugin version. `v42` is your content version. Updating the plugin does not change
your snapshot, and publishing a snapshot does not change the plugin.

**How much does a 60-second check cost?**
One conditional HTTP request. GitHub answers it with `304 Not Modified` when nothing changed, which
does not count against your rate limit. Archives are only downloaded when a new version exists.

**Can I roll back to any version ever published?**
Only to versions this server still has a local backup of — `backup.keep-last` controls how many
(default 5). Older releases stay on GitHub, so you can also just publish one again.

**How does NexoSync reload Nexo?**
It calls Nexo's own full reload — the same one `/nexo reload` runs, covering configs, items, the
resource pack, recipes and dialogs. If a future Nexo version moves that entry point, NexoSync falls
back to dispatching `/nexo reload` on the console instead of breaking. You can force either
mechanism with `nexo.reload.mode` (`api`, `command` or `auto`).

**How does NexoSync know the reload worked?**
It watches for severe errors Nexo logs during the reload, and checks that item definitions are
loaded afterwards. This is **best effort** — Nexo does not expose a definitive success signal. A
reload that silently misbehaves may still be reported as successful, which is why backups matter.

---

## Building from source

Java 21+ is the only prerequisite — the included wrapper fetches Maven itself.

```bash
./mvnw package        # Linux / macOS
mvnw.cmd package      # Windows
```

The plugin lands at `target/NexoSync-1.0.0.jar`. Tests run automatically as part of the build.

---

<div align="center">

**NexoSync** — developed by **Sorvia Development Solutions** and **hugefiz-dev**

Released under the [MIT License](LICENSE).

</div>
