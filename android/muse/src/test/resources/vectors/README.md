These files were written by musecore, the Go core this module replaced, and are the Kotlin port's
cross-language check: `VectorTest`, `PairingVectorTest`, `StoreTest` and `MuseCoreTest` hold the
Kotlin code to them.

- `noise.json`: two Noise_XX_25519_AESGCM_SHA256 handshakes and their transport messages, from
  flynn/noise with reproducible keys.
- `envelope.json`: service frames, the envelopes around them, chunked envelopes and a control stream.
- `pairing.json`: two whole pairing conversations (MTU 23 and 185) between musecore's Controller and
  a phone, every write and notification, with the random streams that make them reproducible.
- `muse-go-paired.json`, `muse-go-unpaired.json`: `muse.json` as musecore's FileStore wrote it. The
  values are fake.

The Go tests that wrote them are `core/internal/muse/vectors_test.go` and
`core/internal/muse/pairing/vectors_test.go` as of the commit that removed `core/`. To rewrite them,
check out that commit's parent and run `MUSE_WRITE_VECTORS=1 go test ./internal/...` in `core/`;
without the variable, the same tests fail if these files no longer match what Go produces.
