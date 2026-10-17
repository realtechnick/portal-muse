# Muse for Portal

Meta's Muse on a Meta Portal (first generation, Android 9), as a sideloaded Android app. No root:
Portal's bootloader stays locked, and Meta's own ADB support is all it needs.

- `core/` is the Go core, `musecore`. It holds the Muse client and the gadget pairing protocol,
  copied from [hey-muse](https://github.com/wobsoriano/hey-muse), the TECHO5 fork that runs Muse on
  Echo devices. It runs as a child process of the app.
- `android/` is the app: the Bluetooth LE side of pairing, the microphone, playback and the screen.
- [PROTOCOL.md](PROTOCOL.md) is how the two talk.

## Status

Tap-to-talk first. An always-on "Hey Muse" wake word comes once the Portal's microphones are
measured on the device.

## Licences

`core/internal/muse` is a modified port of Meta's Muse Gadget SDK (Apache 2.0, see its `LICENSE`).
`core/pico` is SVOX Pico (Apache 2.0). The `hey_muse` wake word model and the rest of what came from
hey-muse are MIT: `LICENSE.hey-muse`, `NOTICE.hey-muse`.
