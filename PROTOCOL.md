# App ↔ musecore protocol

The Android app starts `musecore` (the Go core, shipped as `libmusecore.so` in the APK's native
libraries) as a child process. They talk over the child's stdin and stdout, one JSON object per
line, UTF-8, `\n`-terminated. Every message has a `type`. Byte fields are standard base64 with
padding. `musecore` writes its logs to stderr, never to stdout.

Unknown `type`s are logged and ignored on both sides, so either side can be newer.

## Start-up

```
musecore --data <dir>
```

`<dir>` is the app's private files directory. `musecore` keeps the pairing state there
(`muse.json`, owner-only) and nothing else.

The app sends `config` first. `musecore` answers with `status`, and sends `status` again every
time the connection changes.

## App → musecore

| `type` | Fields | Meaning |
|---|---|---|
| `config` | `name`: string, `sdkToken`: string (may be empty), `ssid`: string (may be empty), `dns`: [string] | The device's display name in the Muse app, the developer's `mgst_` token, the Wi-Fi network the Portal is on, and its DNS servers as `ip` or `ip:port`. Sent at start and whenever one changes. `musecore` (re)starts the Muse client with it. |
| `pair.start` | | Open pairing for `pairing.DefaultWindow`. Refused with `pair.status` `failed` when `sdkToken` is empty. |
| `pair.stop` | | Close pairing. |
| `ble.advertising` | `ok`: bool, `error`: string | Whether the advertisement asked for by `pair.advertise` is on the air. |
| `ble.connected` | `device`: string | A central (the phone) connected to the GATT server. |
| `ble.write` | `data`: base64, `mtu`: int | The central wrote to RX. `mtu` is the negotiated ATT MTU, or 0 when unknown. |
| `ble.disconnected` | | The central went away. |
| `ask` | `id`: string, and exactly one of `text`: string or `wav`: base64 | A question. `wav` is a whole RIFF/WAVE file, 16 kHz mono PCM16. |
| `unpair` | | Forget the pairing. |

## musecore → app

| `type` | Fields | Meaning |
|---|---|---|
| `status` | `phase`: `unpaired` \| `connecting` \| `online` \| `offline`, `error`: string | The Muse connection (`muse.ConnState`). `error` is set only with `offline`. |
| `pair.advertise` | `name`, `service`, `rx`, `tx`: string, `manufacturerId`: int, `manufacturerData`: base64 | Start the GATT server and a connectable advertisement: service UUID in the advertisement with the manufacturer data, `name` in the scan response. RX takes write and write-without-response, TX is read and notify. |
| `pair.unadvertise` | | Stop advertising and close the GATT server. |
| `ble.notify` | `packets`: [base64] | Notify each packet on TX, in order. Wait for each notification to be sent, then about 50 ms (`pairing.ChunkStagger`), before the next. |
| `ble.disconnect` | | Drop the connected central. |
| `pair.status` | `state`: `open` \| `connected` \| `paired` \| `failed` \| `closed`, `name`: string, `error`: string | Pairing progress for the screen. `name` is the `MuseGadgetXXXXXX` the phone will list. |
| `reply` | `id`: string, `kind`: `heard` \| `text` \| `settled` \| `done` \| `error`, `text`: string, `error`: string | Progress on the `ask` with this `id`, from `muse.Heard`, `muse.ReplyText`, `muse.Settled`, the final `muse.Reply`, or a failure (`muse.ErrOffline`, `muse.ErrBusy`, ...). `done` and `error` end the ask. |

## Not yet

Wake-word audio (`audio` frames in, `wake` out) and Muse's device commands (timers, volume,
screen) are left out until the tap-to-talk path works on a Portal.
