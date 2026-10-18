package muse

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/flynn/noise"
)

// vectorDir is where the Kotlin port's tests read what this package produces. The files are
// committed, so the Kotlin check survives without Go: these tests regenerate them and fail if what
// is committed has drifted, and MUSE_WRITE_VECTORS=1 rewrites them.
var vectorDir = filepath.Join("..", "..", "..", "android", "muse", "src", "test", "resources", "vectors")

// detRand is a reproducible byte stream: SHA-256 of the seed and a counter, block after block.
type detRand struct {
	seed    string
	counter uint64
	buf     []byte
}

func (r *detRand) Read(p []byte) (int, error) {
	for n := 0; n < len(p); {
		if len(r.buf) == 0 {
			var c [8]byte
			binary.BigEndian.PutUint64(c[:], r.counter)
			r.counter++
			sum := sha256.Sum256(append([]byte(r.seed), c[:]...))
			r.buf = sum[:]
		}
		k := copy(p[n:], r.buf)
		r.buf = r.buf[k:]
		n += k
	}
	return len(p), nil
}

func checkVector(t *testing.T, name string, v any) {
	t.Helper()
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	b = append(b, '\n')
	writeOrCompare(t, filepath.Join(vectorDir, name), b)
}

func writeOrCompare(t *testing.T, path string, b []byte) {
	t.Helper()
	if os.Getenv("MUSE_WRITE_VECTORS") != "" {
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, b, 0o644); err != nil {
			t.Fatal(err)
		}
		return
	}
	committed, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("%v (run with MUSE_WRITE_VECTORS=1 to write it)", err)
	}
	if !bytes.Equal(committed, b) {
		t.Fatalf("%s no longer matches what Go produces (MUSE_WRITE_VECTORS=1 rewrites it)", path)
	}
}

type noiseMessage struct {
	Payload string `json:"payload"`
	Message string `json:"message"`
}

// noiseTransport is one transport message. A large one is given as a pattern, byte i being i*13,
// and the hash of what it sealed to.
type noiseTransport struct {
	FromInitiator    bool   `json:"from_initiator"`
	Plaintext        string `json:"plaintext,omitempty"`
	PatternLength    int    `json:"pattern_length,omitempty"`
	Ciphertext       string `json:"ciphertext,omitempty"`
	CiphertextSHA256 string `json:"ciphertext_sha256,omitempty"`
}

type noiseVector struct {
	Name               string           `json:"name"`
	Protocol           string           `json:"protocol"`
	InitiatorStatic    string           `json:"initiator_static"`
	InitiatorEphemeral string           `json:"initiator_ephemeral"`
	ResponderStatic    string           `json:"responder_static"`
	ResponderEphemeral string           `json:"responder_ephemeral"`
	Messages           []noiseMessage   `json:"messages"`
	HandshakeHash      string           `json:"handshake_hash"`
	Transport          []noiseTransport `json:"transport"`
}

func makeNoiseVector(t *testing.T, name string, payloads [3]string) noiseVector {
	t.Helper()
	suite := noise.NewCipherSuite(noise.DH25519, noise.CipherAESGCM, noise.HashSHA256)
	keypair := func(seed string) noise.DHKey {
		k, err := suite.GenerateKeypair(&detRand{seed: seed})
		if err != nil {
			t.Fatal(err)
		}
		return k
	}
	iStatic, rStatic := keypair(name+" initiator static"), keypair(name+" responder static")
	iEph, rEph := keypair(name+" initiator ephemeral"), keypair(name+" responder ephemeral")
	initiator, err := noise.NewHandshakeState(noise.Config{
		CipherSuite: suite, Random: &detRand{seed: name + " initiator ephemeral"}, Pattern: noise.HandshakeXX,
		Initiator: true, StaticKeypair: iStatic,
	})
	if err != nil {
		t.Fatal(err)
	}
	responder, err := noise.NewHandshakeState(noise.Config{
		CipherSuite: suite, Random: &detRand{seed: name + " responder ephemeral"}, Pattern: noise.HandshakeXX,
		StaticKeypair: rStatic,
	})
	if err != nil {
		t.Fatal(err)
	}
	v := noiseVector{
		Name:               name,
		Protocol:           "Noise_XX_25519_AESGCM_SHA256",
		InitiatorStatic:    hex.EncodeToString(iStatic.Private),
		InitiatorEphemeral: hex.EncodeToString(iEph.Private),
		ResponderStatic:    hex.EncodeToString(rStatic.Private),
		ResponderEphemeral: hex.EncodeToString(rEph.Private),
	}
	var iSend, iRecv, rSend, rRecv *noise.CipherState
	for i, payload := range payloads {
		writer, reader := initiator, responder
		if i == 1 {
			writer, reader = responder, initiator
		}
		msg, cs1, cs2, err := writer.WriteMessage(nil, []byte(payload))
		if err != nil {
			t.Fatal(err)
		}
		got, rc1, rc2, err := reader.ReadMessage(nil, msg)
		if err != nil || string(got) != payload {
			t.Fatalf("message %d: %q, %v", i+1, got, err)
		}
		v.Messages = append(v.Messages, noiseMessage{Payload: hex.EncodeToString([]byte(payload)), Message: hex.EncodeToString(msg)})
		if i == 2 {
			iSend, iRecv, rRecv, rSend = cs1, cs2, rc1, rc2
		}
	}
	if !bytes.Equal(initiator.ChannelBinding(), responder.ChannelBinding()) {
		t.Fatal("the two sides disagree on the handshake hash")
	}
	v.HandshakeHash = hex.EncodeToString(initiator.ChannelBinding())
	big := make([]byte, maxChunkPayload+16)
	for i := range big {
		big[i] = byte(i * 13)
	}
	for _, step := range []struct {
		fromInitiator bool
		plaintext     []byte
	}{
		{true, []byte("first from the device")},
		{false, []byte("first from the VM")},
		{true, nil},
		{true, []byte("third from the device")},
		{false, big},
		{false, []byte("last")},
	} {
		send, recv := iSend, rRecv
		if !step.fromInitiator {
			send, recv = rSend, iRecv
		}
		sealed, err := send.Encrypt(nil, nil, step.plaintext)
		if err != nil {
			t.Fatal(err)
		}
		if opened, err := recv.Decrypt(nil, nil, sealed); err != nil || !bytes.Equal(opened, step.plaintext) {
			t.Fatalf("transport did not round trip: %v", err)
		}
		entry := noiseTransport{FromInitiator: step.fromInitiator}
		if len(step.plaintext) > 1024 {
			sum := sha256.Sum256(sealed)
			entry.PatternLength, entry.CiphertextSHA256 = len(step.plaintext), hex.EncodeToString(sum[:])
		} else {
			entry.Plaintext, entry.Ciphertext = hex.EncodeToString(step.plaintext), hex.EncodeToString(sealed)
		}
		v.Transport = append(v.Transport, entry)
	}
	return v
}

func TestNoiseVectors(t *testing.T) {
	checkVector(t, "noise.json", []noiseVector{
		// What the client and the VM send: no handshake payloads.
		makeNoiseVector(t, "empty payloads", [3]string{"", "", ""}),
		makeNoiseVector(t, "payloads", [3]string{"one", "two, from the responder", "three"}),
	})
}

type headerVector struct {
	Key   string `json:"key"`
	Value string `json:"value"`
}

type frameVector struct {
	Stream  int64          `json:"stream"`
	Kind    string         `json:"kind"`
	Verb    string         `json:"verb,omitempty"`
	Path    string         `json:"path,omitempty"`
	Headers []headerVector `json:"headers,omitempty"`
	Status  int            `json:"status,omitempty"`
	Data    string         `json:"data,omitempty"`
	EndBody bool           `json:"end_body,omitempty"`
	Code    int            `json:"code,omitempty"`
	Reason  string         `json:"reason,omitempty"`
	// Frame is encodeFrame, Request encodeServiceRequest, and Response the envelope the VM would
	// wrap it in.
	Frame    string `json:"frame"`
	Request  string `json:"request"`
	Response string `json:"response"`
}

type chunkVector struct {
	Length int    `json:"length"`
	Seed   string `json:"seed"`
	// Pieces and Heads leave out field 1, the random id that every piece of one envelope shares.
	Pieces []string `json:"pieces_without_id_sha256"`
	Heads  []string `json:"pieces_without_id_head"`
	Total  string   `json:"envelope_sha256"`
}

type controlVector struct {
	Stream   string              `json:"stream"`
	Messages []map[string]string `json:"messages"`
}

type envelopeVectors struct {
	Frames  []frameVector `json:"frames"`
	Chunks  []chunkVector `json:"chunks"`
	Control controlVector `json:"control"`
}

var kindNames = map[frameKind]string{frameRequest: "request", frameResponse: "response", frameBody: "body", frameReset: "reset"}

func envelopeBytes(seed string, n int) []byte {
	b := make([]byte, n)
	_, _ = (&detRand{seed: seed}).Read(b)
	return b
}

func TestEnvelopeVectors(t *testing.T) {
	var out envelopeVectors
	for _, f := range []frame{
		{stream: 1, kind: frameRequest, verb: "POST", path: "/link-control"},
		{stream: 2, kind: frameRequest, verb: "POST", path: "/chat/stream",
			headers: []header{{"Content-Type", "application/json"}, {"x-request-id", "9b2c7d4e-0f1a-4b3c-8d5e-6f7a8b9c0d1e"}, {"x-app-id", "musegadget"}, {"empty", ""}, {"", "keyless"}},
			data:    []byte(`{"message":"hello","output_modality":"text","device_id":"homelink-a1b2c3"}`), endBody: true},
		{stream: 3, kind: frameResponse, status: 200, headers: []header{{"a", "b"}}, data: []byte("ok"), endBody: true},
		{stream: 3, kind: frameResponse, status: 403},
		{stream: 3, kind: frameResponse, status: -1},
		{stream: 1 << 40, kind: frameBody, data: []byte{0, 1, 2}},
		{stream: 4, kind: frameBody, endBody: true},
		{stream: 5, kind: frameReset, code: resetCanceled, reason: "canceled"},
		{stream: 6, kind: frameReset},
		{stream: 7, kind: frameBody, data: envelopeBytes("big body", 16400)},
		{stream: 0, kind: frameBody, data: []byte("é ü")},
	} {
		encoded := encodeFrame(f)
		back, err := decodeFrame(encoded)
		if err != nil {
			t.Fatal(err)
		}
		if back.stream != f.stream || back.kind != f.kind {
			t.Fatalf("%+v did not round trip", f)
		}
		v := frameVector{
			Stream: f.stream, Kind: kindNames[f.kind], Verb: f.verb, Path: f.path, Status: f.status,
			Data: hex.EncodeToString(f.data), EndBody: f.endBody, Code: f.code, Reason: f.reason,
			Frame: hex.EncodeToString(encoded), Request: hex.EncodeToString(encodeServiceRequest(f)),
			Response: hex.EncodeToString(appendBytesField(nil, 1, encoded)),
		}
		for _, h := range f.headers {
			v.Headers = append(v.Headers, headerVector{h.key, h.value})
		}
		out.Frames = append(out.Frames, v)
	}

	for _, n := range []int{1, 3, maxChunkPayload, maxChunkPayload + 1, 3*maxChunkPayload + 17} {
		seed := "chunk " + string(rune('a'+len(out.Chunks)))
		env := envelopeBytes(seed, n)
		pieces, err := chunk(env)
		if err != nil {
			t.Fatal(err)
		}
		var a assembler
		v := chunkVector{Length: n, Seed: seed}
		var firstID []byte
		for i, p := range pieces {
			id, rest := splitChunkID(p)
			if i == 0 {
				firstID = id
			} else if !bytes.Equal(id, firstID) {
				t.Fatal("the pieces of one envelope carry different ids")
			}
			sum := sha256.Sum256(rest)
			v.Pieces = append(v.Pieces, hex.EncodeToString(sum[:]))
			v.Heads = append(v.Heads, hex.EncodeToString(rest[:min(len(rest), 24)]))
			whole, err := a.add(p)
			if err != nil || (i == len(pieces)-1) != (whole != nil) || (whole != nil && !bytes.Equal(whole, env)) {
				t.Fatalf("chunks of %d bytes did not reassemble: %v", n, err)
			}
		}
		sum := sha256.Sum256(env)
		v.Total = hex.EncodeToString(sum[:])
		out.Chunks = append(out.Chunks, v)
	}

	type result struct {
		Method string `json:"method"`
		ID     string `json:"id"`
		OK     bool   `json:"ok"`
	}
	var stream []byte
	for _, m := range []any{
		map[string]any{"method": "link.invoke", "id": "a", "command": "echo", "params": map[string]any{"text": "hi"}},
		result{Method: "link.result", ID: "b", OK: true},
		map[string]any{"type": "evt", "event": "link.unpaired"},
	} {
		b, err := encodeControl(m)
		if err != nil {
			t.Fatal(err)
		}
		stream = append(stream, b...)
		if len(out.Control.Messages) == 0 {
			stream = append(stream, 0, 0, 0, 0)
		}
		var d controlDecoder
		messages, err := d.feed(b)
		if err != nil || len(messages) != 1 {
			t.Fatal(err)
		}
		fields := map[string]string{}
		for key, raw := range map[string]json.RawMessage{"id": messages[0].ID, "method": messages[0].Method, "event": messages[0].Event, "command": messages[0].Command, "params": messages[0].Params} {
			if raw != nil {
				fields[key] = string(raw)
			}
		}
		out.Control.Messages = append(out.Control.Messages, fields)
	}
	out.Control.Stream = hex.EncodeToString(append(stream, 5, 0, 0, 0, 'n', 'o', 'p', 'e', '!'))
	checkVector(t, "envelope.json", out)
}

// The state file exactly as FileStore writes it, which the Kotlin store has to keep reading so a
// device paired by musecore stays paired. Every value is fake.
func TestStoreVector(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "muse.json")
	st := State{
		Identity: Identity{MAC: "02:11:22:a1:b2:c3"},
		Credentials: &Credentials{
			AccessToken: "fake-access-token", RefreshToken: "hatch_refresh:fake-refresh-token", TokenType: "device",
			Username: "someone@example.com", APIURL: "https://legacy.example", APIURLV2: "https://api.example",
			NoiseHost: "noise.example", AccessTokenSavedAt: 1760000000,
		},
		SDKTokenReported: true,
	}
	if err := (FileStore{Path: path}).Save(st); err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	writeOrCompare(t, filepath.Join(vectorDir, "muse-go-paired.json"), b)

	if err := (FileStore{Path: path}).Save(State{Identity: st.Identity}); err != nil {
		t.Fatal(err)
	}
	if b, err = os.ReadFile(path); err != nil {
		t.Fatal(err)
	}
	writeOrCompare(t, filepath.Join(vectorDir, "muse-go-unpaired.json"), b)
}

// splitChunkID cuts a chunk's leading id field, which chunk writes first when the id is not zero.
func splitChunkID(p []byte) (id, rest []byte) {
	if len(p) == 0 || p[0] != 0x08 {
		return nil, p
	}
	r := protoReader{b: p[1:]}
	r.varint()
	n := len(p) - len(r.b)
	return p[:n], p[n:]
}
