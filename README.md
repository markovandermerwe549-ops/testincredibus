# Tabletop Lab VR

A VR tabletop sandbox for Meta Quest 2, Quest 3, and Quest 3S using the supplied Blender models. Touch controllers appear as cyan and orange ball hands.

## Download

[Download Tabletop-Lab-VR.apk](https://github.com/markovandermerwe549-ops/testincredibus/releases/download/v1.0.0/Tabletop-Lab-VR.apk) (about 84 MiB).

## Install

Enable Quest Developer Mode, connect the headset over USB, and accept USB debugging. Install the APK with SideQuest, or run:

```sh
adb install -r Tabletop-Lab-VR.apk
```

Launch **Tabletop Lab VR** from the headset's **Unknown Sources** library filter.

## Controls

- Move either ball hand close to a model and hold **Grip** to pick it up.
- Release **Grip** to drop; move your hand while releasing to throw.
- **A / X:** reset all four props to the table.
- **B / Y:** recenter your position and facing relative to the table.

Uses Touch controllers; bare-hand tracking is not implemented.

## Build information

- Android package: `com.tabletoplab.vr`, version `1.0`.
- ARM64, Meta OpenXR, development-signed sideload APK.
- All four supplied Blender files are represented, including the `.blend1` backup.
- Local rendering and automated physics checks passed for collisions, pickup, exclusive ownership, hand following, release, throwing, and resets.
- The APK signature was verified. Testing on a physical Quest headset has not been performed.

SHA-256:

```text
e883163b7db40a1b0329e65a07c743dfb5d6e64db4621ce188a91e04ad63cfeb
```
