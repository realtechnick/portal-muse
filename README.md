# Muse for Portal

Meta's Muse on a Meta Portal (first generation, Android 9), as a sideloaded Android app. No root:
Portal's bootloader stays locked, and Meta's own ADB support is all it needs.

- `android/muse` is the Muse library, plain Kotlin with no Android code, so its tests run on a PC. It
  holds the Muse client and the gadget pairing protocol, ported to Kotlin from the Go core of
  [hey-muse](https://github.com/wobsoriano/hey-muse), the TECHO5 fork that runs Muse on Echo devices.
  `MuseCore` is the app's way in: config, pairing, BLE events and questions go in, and the
  connection, pairing and reply status come out.
- `android/app` is the app: the Bluetooth LE side of pairing, the microphone, playback and the
  screen. It runs `MuseCore` in its own process.
- `android/probe` is a separate app that reports what a Portal allows on day one.
- `assets/wake` is the "Hey Muse" wake word model, for when the app listens for it.
- `third_party/pico` is the SVOX Pico speech engine.

## Status

Tap-to-talk first. An always-on "Hey Muse" wake word comes once the Portal's microphones are
measured on the device.

## Build

You need JDK 21 and the Android SDK (platform 35).

```sh
android/gradlew -p android :muse:test :app:testDebugUnitTest :app:assembleDebug :probe:assembleDebug
```

`:muse:test` includes the cross-language check: the Noise sessions, frames, chunks, pairing
conversations and state files that the Go core wrote are committed under
`android/muse/src/test/resources/vectors`, and the Kotlin code has to reproduce them byte for byte.
That folder's README says how they were made.

## Install on a Portal

Turn on **Settings > Debug > ADB Enabled** on the Portal, connect it over USB-C and allow the
computer. Then:

```sh
adb install -g android/probe/build/outputs/apk/debug/probe-debug.apk
adb shell am start -n dev.wobsoriano.museportal.probe/.ProbeActivity
adb logcat -s MuseProbe        # the day-one report
adb shell am start -n dev.wobsoriano.museportal.probe/.ProbeActivity -S --es only wake   # a minute of "Hey Muse"
adb push vosk-model-en-us-0.22-lgraph /sdcard/Android/data/dev.wobsoriano.museportal.probe/files/
adb shell am start -n dev.wobsoriano.museportal.probe/.ProbeActivity -S --es only vosk   # two minutes of "Hey Muse" on Vosk
adb install -g android/app/build/outputs/apk/debug/app-debug.apk
```

The app keeps its pairing in `muse.json` in its files directory, in the format the Go core wrote,
so a Portal paired by an earlier build stays paired.

The Vosk check saves its audio as `files/vosk.wav`. `tools/wakeeval/eval.py` replays a WAV through the
same rule on the PC and scores it against a file of attempt times.

## Licences

`android/muse` is a modified port of Meta's Muse Gadget SDK (Apache 2.0, see its `LICENSE`), by way
of hey-muse's Go port. Its test vectors in `android/muse/src/test/resources/pairing` are Meta's
(Apache 2.0). `third_party/pico` is SVOX Pico (Apache 2.0). The `hey_muse` wake word model and the
rest of what came from hey-muse are MIT: `LICENSE.hey-muse`, `NOTICE.hey-muse`.
