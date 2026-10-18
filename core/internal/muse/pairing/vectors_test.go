package pairing

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"
)

// vectorDir is where the Kotlin port's tests read what this package produces (see the muse
// package's vectors_test.go).
var vectorDir = filepath.Join("..", "..", "..", "..", "android", "muse", "src", "test", "resources", "vectors")

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

type recordingLink struct {
	mtu  int
	asm  Assembler
	sent chan []byte
	done chan []byte
}

func (l *recordingLink) MTU() int          { return l.mtu }
func (l *recordingLink) Disconnect() error { return nil }

func (l *recordingLink) Send(packets [][]byte) error {
	for _, p := range packets {
		l.sent <- append([]byte(nil), p...)
		if _, ok := l.asm.Feed(p); ok {
			l.done <- nil
		}
	}
	return nil
}

type pairingStep struct {
	Note          string   `json:"note"`
	Writes        []string `json:"writes"`
	Messages      int      `json:"messages"`
	Notifications []string `json:"notifications"`
}

type pairingTranscript struct {
	MTU        int           `json:"mtu"`
	DeviceSeed string        `json:"device_seed"`
	PhoneSeed  string        `json:"phone_seed"`
	Device     Device        `json:"device"`
	SDKToken   string        `json:"sdk_token"`
	SSID       string        `json:"ssid"`
	Steps      []pairingStep `json:"steps"`
	Saved      Result        `json:"saved"`
}

// vectorPhone is the phone's half, built from this package's primitives so that every byte it
// sends is reproducible.
type vectorPhone struct {
	rand      *detRand
	key       []byte
	pub       []byte
	nonce     []byte
	keys      sessionKeys
	sessionID string
	counter   uint64
}

func (p *vectorPhone) hello(t *testing.T) map[string]any {
	key, err := generateKey(p.rand)
	if err != nil {
		t.Fatal(err)
	}
	p.key, p.pub = key.Bytes(), key.PublicKey().Bytes()
	p.nonce = make([]byte, nonceBytes)
	_, _ = p.rand.Read(p.nonce)
	return map[string]any{
		"action": actionHello, "version": pairingVersion, "pairing_auth": authCommunity, "pairing_policy": policyApp,
		"mobile_pub": b64url(p.pub), "mobile_nonce": b64url(p.nonce),
	}
}

func (p *vectorPhone) accept(t *testing.T, dev Device, ready map[string]any) {
	devicePub := unb64(t, ready["device_pub"].(string))
	deviceNonce := unb64(t, ready["device_nonce"].(string))
	key, err := generateKey(bytes.NewReader(p.key))
	if err != nil {
		t.Fatal(err)
	}
	peer, err := parseHello(fields{
		"version": json.RawMessage("5"), "pairing_auth": json.RawMessage(`"none"`), "pairing_policy": json.RawMessage(`"confirm_app"`),
		"mobile_pub": json.RawMessage(`"` + b64url(devicePub) + `"`), "mobile_nonce": json.RawMessage(`"` + b64url(deviceNonce) + `"`),
	})
	if err != nil {
		t.Fatal(err)
	}
	secret, err := key.ECDH(peer.peer)
	if err != nil {
		t.Fatal(err)
	}
	hash := sha256.Sum256([]byte(buildTranscript(dev, b64url(p.pub), b64url(devicePub), b64url(p.nonce), b64url(deviceNonce))))
	if b64url(hash[:]) != ready["transcript_hash"] {
		t.Fatal("the phone and the device hashed different transcripts")
	}
	if p.keys, err = deriveSessionKeys(secret, p.nonce, deviceNonce, hash[:]); err != nil {
		t.Fatal(err)
	}
	p.sessionID = ready["session_id"].(string)
	p.counter = 0
}

func (p *vectorPhone) seal(t *testing.T, command map[string]any) map[string]any {
	gcm, err := newGCM(p.keys.mobileTX)
	if err != nil {
		t.Fatal(err)
	}
	plaintext, _ := json.Marshal(command)
	counter := p.counter
	p.counter++
	sealed := gcm.Seal(nil, recordNonce(toDevice, counter), plaintext, recordAAD(p.sessionID, toDevice, counter))
	return map[string]any{
		"action": actionEncrypted, "session_id": p.sessionID, "counter": strconv.FormatUint(counter, 10),
		"ciphertext": b64url(sealed[:len(sealed)-tagBytes]), "tag": b64url(sealed[len(sealed)-tagBytes:]),
	}
}

func makePairingTranscript(t *testing.T, mtu int) pairingTranscript {
	dev := Device{NodeID: "homelink-0a1b2c", BLEName: "MuseGadget0A1B2C", MAC: "02:11:22:0a:1b:2c", Version: "0.9.0"}
	tr := pairingTranscript{
		MTU: mtu, DeviceSeed: "pairing device " + strconv.Itoa(mtu), PhoneSeed: "pairing phone " + strconv.Itoa(mtu),
		Device: dev, SDKToken: "mgst_fake_vector_token", SSID: "HomeNet",
	}
	l := &recordingLink{mtu: mtu, sent: make(chan []byte, 1024), done: make(chan []byte, 64)}
	saved := make(chan Result, 1)
	c, err := New(Config{
		Device:    dev,
		Transport: l,
		SDKToken:  tr.SDKToken,
		Network:   AlreadyOnline{Reachable: func(context.Context) bool { return true }, SSID: func() string { return tr.SSID }},
		Verify:    func(context.Context, Result) error { return nil },
		Save:      func(r Result) error { saved <- r; return nil },
		Rand:      &detRand{seed: tr.DeviceSeed},
	})
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- c.Run(context.Background()) }()

	var last []byte
	step := func(note string, command map[string]any, messages int) {
		raw, _ := json.Marshal(command)
		packets, err := EncodeChunks(raw, mtu)
		if err != nil {
			t.Fatal(err)
		}
		s := pairingStep{Note: note, Messages: messages}
		for _, p := range packets {
			s.Writes = append(s.Writes, hex.EncodeToString(p))
			c.OnWrite(p)
		}
		var asm Assembler
		for got := 0; got < messages; {
			select {
			case p := <-l.sent:
				s.Notifications = append(s.Notifications, hex.EncodeToString(p))
				if m, ok := asm.Feed(p); ok {
					last = m
					got++
				}
			case <-time.After(3 * time.Second):
				t.Fatalf("%s: the device sent %d of %d messages", note, got, messages)
			}
		}
		tr.Steps = append(tr.Steps, s)
	}
	reply := func() map[string]any {
		var m map[string]any
		if err := json.Unmarshal(last, &m); err != nil {
			t.Fatalf("%q: %v", last, err)
		}
		return m
	}

	phone := &vectorPhone{rand: &detRand{seed: tr.PhoneSeed}}
	step("device info", map[string]any{"action": actionDeviceInfo}, 1)
	step("plaintext wifi_scan", map[string]any{"action": actionWiFiScan}, 1)
	step("hello", phone.hello(t), 1)
	phone.accept(t, dev, reply())
	step("client finished", phone.seal(t, map[string]any{"action": actionClientFinished}), 1)
	step("unknown action", phone.seal(t, map[string]any{"action": "dance"}), 1)
	step("wifi scan", phone.seal(t, map[string]any{"action": actionWiFiScan}), 1)
	step("provision", phone.seal(t, map[string]any{
		"action": actionProvision, "ssid": "HomeNet", "password": "pw",
		"access_token": "fake-device-access", "refresh_token": "fake-device-refresh", "token_type": "device",
		"username": "someone", "api_url": "https://legacy-api.example", "api_url_v2": "https://api.example",
		"noise_host": "noise.example",
	}), 3)
	if err := <-done; err != nil {
		t.Fatalf("Run = %v", err)
	}
	tr.Saved = <-saved
	return tr
}

func TestPairingVectors(t *testing.T) {
	var transcripts []pairingTranscript
	for _, mtu := range []int{23, 185} {
		transcripts = append(transcripts, makePairingTranscript(t, mtu))
	}
	b, err := json.MarshalIndent(transcripts, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(vectorDir, "pairing.json")
	b = append(b, '\n')
	if os.Getenv("MUSE_WRITE_VECTORS") != "" {
		if err := os.MkdirAll(vectorDir, 0o755); err != nil {
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
