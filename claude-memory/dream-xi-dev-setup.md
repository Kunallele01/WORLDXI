---
name: dream-xi-dev-setup
description: "Dream XI dev environment — migrated to a fresh laptop (2026-11-02); emulator-only now, no physical device"
metadata: 
  type: project
  modified: 2026-11-02
---

**Superseded 2026-11-02:** the user migrated Dream XI (ERA XI) to a brand new
laptop and will NOT connect their physical Android phone to it (virus/safety
concern). From this point on, build/test/verify on an **Android emulator
(AVD) only** — do not suggest `adb install` to a physical device or ask the
user to reconnect one. Create an AVD via Android Studio's Device Manager
(a Pixel profile, API 34+, x86_64/arm64 system image) and run/install there.

The old guidance (physical phone connected via USB debugging, and that
`adb exec-out screencap` returns broken/partial frames on that device) applied
to the OLD machine/device and should not be assumed to hold for the emulator —
screenshotting an emulator via `adb exec-out screencap` or Android Studio's
own screenshot tool is expected to work normally; verify it does before
relying on it.

See [[dream-xi-repo-state]] for what the new laptop needs installed and how
the repo/data/secrets were carried over.
