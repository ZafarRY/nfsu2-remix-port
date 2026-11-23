<p align="center">
  <img src="android/app/src/main/res/drawable/icono.png" alt="NFSU2 Remix logo" width="200">
</p>

<h1 align="center">NFSU2 Remix</h1>

<p align="center">
  <b>Need for Speed: Underground 2, natively recompiled for Android.</b><br>
  No emulator. No Wine. Just the original game running on Vulkan.
</p>

<p align="center">
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%2010%2B-3DDC84?logo=android&logoColor=white">
  <img alt="ABI" src="https://img.shields.io/badge/ABI-arm64--v8a-blue">
  <img alt="Graphics" src="https://img.shields.io/badge/graphics-Vulkan%201.1%20%2F%20DXVK-red">
  <img alt="License" src="https://img.shields.io/badge/license-MIT-green">
  <img alt="Status" src="https://img.shields.io/badge/status-experimental-orange">
</p>

---

## What is this?

NFSU2 Remix is an **unofficial remix** of
[codepdbh/nfsu2-recompiled](https://github.com/codepdbh/nfsu2-recompiled), the Android port of
*Need for Speed: Underground 2*. The original `SPEED2.EXE` is statically recompiled into C and
runs natively on Android, with DirectX 9 translated to Vulkan through DXVK.

This remix keeps that work intact and adds an English launcher, a cleaner repository, and a
self-contained build pipeline.

> **Status:** experimental, not an official release. Please do **not** report bugs from this remix to
> the original developer. Use this repository's issue tracker instead.

## Features

**Graphics and performance**
- Internal resolution: native, 75%, 50%, or 1280×720
- FPS limit: 30 / 60 / 90 / 120 / unlimited
- Widescreen mode with corrected HUD and camera (or original stretched 4:3)
- Minimap position: top left or the original bottom left
- CPU/GPU sync modes: parallel for performance, or low latency
- Compressed textures decoded on the GPU, or on the CPU for compatibility
- One-tap graphics cache reset (shaders are rebuilt on the next launch)

**GPU drivers**
- Built-in Vulkan driver tester that reports GPU, Vulkan version, and missing features
- Import custom driver packages (such as Mesa Turnip or PanVK) from a ZIP
- Per-GPU advice on which driver to use (Adreno, Mali, PowerVR, Xclipse)
- Automatic light-settings suggestion on weaker GPUs

**Controls**
- Touch controls with three styles: Classic, Xbox, and PlayStation
- Tilt steering with adjustable sensitivity and calibration
- NOS + gas macro
- Save and load your own named touch layouts
- Gamepad support

**Multiplayer**
- Online mode through a community server (the original EA servers are shut down)
- LAN races, including hosting when a matching `server.dll` is present

**Quality of life**
- Multi-language support (reads whatever is in your `LANGUAGES` folder)
- Built-in crash reporter that bundles logs into a ZIP and opens a pre-filled GitHub issue

## Requirements

- Android 10 or newer
- An `arm64-v8a` device with **Vulkan 1.1** or newer
- **Your own legitimate copy of Need for Speed: Underground 2 for PC.**
  This repository does not contain any game files.

## Installation

1. Download the latest APK from the [Releases](../../releases) page and install it. If you used a
   build signed with a different key before, uninstall it first.
2. Copy the contents of your PC game folder (`SPEED2.EXE`, `GLOBAL`, `TRACKS`, `CARS`, `FRONTEND`,
   `LANGUAGES`, and so on) to internal storage in a folder named `nfsu2`:

   ```
   /sdcard/nfsu2/
   ├── SPEED2.EXE
   ├── GLOBAL/
   ├── TRACKS/
   ├── CARS/
   ├── FRONTEND/
   └── LANGUAGES/
   ```

3. Open **NFSU2 Remix**, grant file access, choose your language, resolution, and FPS limit, then tap
   **PLAY**.

The launcher shows a checklist of the required folders, so you can see at a glance what is missing.

### Optional: widescreen HUD

Place `NFSUnderground2.WidescreenFix.dat` in `nfsu2/scripts/` to get the corrected widescreen HUD.

## Troubleshooting

- **Black screen or crash on start:** open the launcher, run **Test driver**, and check for missing
  Vulkan features. Try the system driver if you imported a custom one.
- **Slow game or stuttering audio:** lower the internal resolution to 50% and the FPS limit to 30 or 60.
- **Graphical glitches after a driver change:** use **Reset graphics cache**.
- **Game says a language file is missing:** make sure `LANGUAGES/<language>.bin` exists in `nfsu2/`.
- **Still stuck:** use **Report a problem** in the launcher. It saves a full log ZIP and opens a
  pre-filled issue; please attach the ZIP.

## Credits and license

- Original project: [codepdbh/nfsu2-recompiled](https://github.com/codepdbh/nfsu2-recompiled) by
  **codepdbh**, built on [pcrecomp](https://github.com/sp00nznet/pcrecomp) and DXVK. All of the
  recompilation work, the Android port, and the original launcher are theirs.
- DXVK is licensed under the zlib license (see `DXVK-LICENSE`).
- Tooling and remix code are released under the **MIT License**, following the original repository.
  See [`LICENSE`](LICENSE).

## Disclaimer

*Need for Speed*, *Underground*, EA, and all related game assets are the property of
**Electronic Arts Inc.** This project is not affiliated with or endorsed by EA or the original
developer. It does not include any game files, music, video, or 3D models from anyone. You must own
a legitimate copy of the game to use it.