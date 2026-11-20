<div align="center">

<img src=".github/assets/icon-nbg.png" alt="NexoSync" width="180">

# NexoSync

### One server edits. Every server updates.

**Keep your Nexo items, glyphs and resource packs identical across an entire network — using nothing but a GitHub repository.**

![Paper](https://img.shields.io/badge/Paper-1.21+-0288D1?style=for-the-badge)
![Folia](https://img.shields.io/badge/Folia-supported-7E57C2?style=for-the-badge)
![Java](https://img.shields.io/badge/Java-21+-E76F00?style=for-the-badge)

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

[![Watch the demo on YouTube](https://img.shields.io/badge/Watch_the_demo-YouTube-FF0000?style=for-the-badge&logo=youtube&logoColor=white)](https://www.youtube.com/watch?v=XHST7RUHpL4)

*A publisher pushes a snapshot and a receiver picks it up — the whole loop, start to finish.*

</div>

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

## Building from source

Java 21+ is the only prerequisite — the included wrapper fetches Maven itself.

```bash
./mvnw package        # Linux / macOS
mvnw.cmd package      # Windows
```

The plugin jar lands in the `target/` directory. Tests run automatically as part of the build.

---

<div align="center">

**NexoSync** — developed by **Sorvia Development Solutions** and **hugefiz-dev**

Released under the [MIT License](LICENSE).

</div>
