# VR ADB Executor

A small Android app for Meta Quest (and any Android device) that connects to **wireless ADB** and runs shell commands from a normal 2D panel. It shows up in your app library like any other app.

## Download

[Download VR-ADB-Executor.apk](https://github.com/markovandermerwe549-ops/testincredibus/raw/refs/heads/claude/wireless-adb-vr-apk-w001ee/VR-ADB-Executor.apk) (about 29 KB)

## Install on Quest

1. Turn on Developer Mode for the headset in the Meta Horizon phone app.
2. Plug the headset into a PC and run:
   ```sh
   adb install -r VR-ADB-Executor.apk
   adb tcpip 5555
   ```
   (Or install with SideQuest.)
3. In the headset open **Library → Unknown Sources → VR ADB Executor**.

`adb tcpip 5555` turns on wireless ADB. You have to run it again after the headset restarts.

## Use

- **Connect**: type the IP address and port (default `5555`). Press **This headset** to control the Quest itself (`127.0.0.1`). To control another device, type its Wi-Fi IP address.
- The first time, an **Allow debugging?** prompt appears on the target. Tick **Always allow** and accept it.
- Type a shell command and press **Execute**. While a long command such as `logcat` runs, the button changes to **Stop**.
- **Undo / Redo** step backward and forward through your edits in the command box.
- **Clear command** empties the command box. **Clear** empties the output.
- Quick buttons fill in common commands: model, battery, installed apps, IP address, storage, top, and uptime.

## Limits

- Only classic ADB over TCP (`adb tcpip`) is supported. The Android 11+ **Wireless debugging** pairing code flow is not supported.
- Commands run as the ADB `shell` user, with the same permissions as `adb shell` from a PC.
- Tested against a simulated adbd. The key and signature format were checked against the official `adb` tool. It has not been tested on a physical headset.

## Build

On Ubuntu:

```sh
sudo apt install aapt apksigner zipalign dalvik-exchange android-sdk-platform-23 default-jdk
./build.sh
```

The source is plain Java with no libraries:
- `app/src/com/vradb/executor/AdbConnection.java`: ADB protocol (connect, RSA authentication, shell)
- `app/src/com/vradb/executor/AdbKey.java`: the app's ADB key
- `app/src/com/vradb/executor/MainActivity.java`: the UI

`debug.keystore` signs the APK so that new builds install over old ones. Its password is `android`.
