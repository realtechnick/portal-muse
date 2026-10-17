// Package protocol is the line protocol between the Android app and musecore (PROTOCOL.md): one
// JSON object per line, told apart by its "type".
package protocol

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
)

// Message is one line of the protocol.
type Message interface{ Type() string }

// App → musecore.
type (
	Config struct {
		Name     string   `json:"name"`
		SDKToken string   `json:"sdkToken"`
		SSID     string   `json:"ssid"`
		DNS      []string `json:"dns"`
	}
	PairStart      struct{}
	PairStop       struct{}
	BLEAdvertising struct {
		OK    bool   `json:"ok"`
		Error string `json:"error"`
	}
	BLEConnected struct {
		Device string `json:"device"`
	}
	BLEWrite struct {
		Data []byte `json:"data"`
		MTU  int    `json:"mtu"`
	}
	BLEDisconnected struct{}
	// Ask carries exactly one of Text and WAV; a nil field is one the app left out.
	Ask struct {
		ID   string  `json:"id"`
		Text *string `json:"text,omitempty"`
		WAV  []byte  `json:"wav,omitempty"`
	}
	Unpair struct{}
)

// musecore → app.
type (
	Status struct {
		Phase string `json:"phase"`
		Error string `json:"error"`
	}
	PairAdvertise struct {
		Name             string `json:"name"`
		Service          string `json:"service"`
		RX               string `json:"rx"`
		TX               string `json:"tx"`
		ManufacturerID   int    `json:"manufacturerId"`
		ManufacturerData []byte `json:"manufacturerData"`
	}
	PairUnadvertise struct{}
	BLENotify       struct {
		Packets [][]byte `json:"packets"`
	}
	BLEDisconnect struct{}
	PairStatus    struct {
		State string `json:"state"`
		Name  string `json:"name"`
		Error string `json:"error"`
	}
	Reply struct {
		ID    string `json:"id"`
		Kind  string `json:"kind"`
		Text  string `json:"text"`
		Error string `json:"error"`
	}
)

func (Config) Type() string          { return "config" }
func (PairStart) Type() string       { return "pair.start" }
func (PairStop) Type() string        { return "pair.stop" }
func (BLEAdvertising) Type() string  { return "ble.advertising" }
func (BLEConnected) Type() string    { return "ble.connected" }
func (BLEWrite) Type() string        { return "ble.write" }
func (BLEDisconnected) Type() string { return "ble.disconnected" }
func (Ask) Type() string             { return "ask" }
func (Unpair) Type() string          { return "unpair" }
func (Status) Type() string          { return "status" }
func (PairAdvertise) Type() string   { return "pair.advertise" }
func (PairUnadvertise) Type() string { return "pair.unadvertise" }
func (BLENotify) Type() string       { return "ble.notify" }
func (BLEDisconnect) Type() string   { return "ble.disconnect" }
func (PairStatus) Type() string      { return "pair.status" }
func (Reply) Type() string           { return "reply" }

var decoders = map[string]func([]byte) (Message, error){}

func register[M Message]() {
	var zero M
	decoders[zero.Type()] = func(b []byte) (Message, error) {
		var m M
		err := json.Unmarshal(b, &m)
		return m, err
	}
}

func init() {
	register[Config]()
	register[PairStart]()
	register[PairStop]()
	register[BLEAdvertising]()
	register[BLEConnected]()
	register[BLEWrite]()
	register[BLEDisconnected]()
	register[Ask]()
	register[Unpair]()
	register[Status]()
	register[PairAdvertise]()
	register[PairUnadvertise]()
	register[BLENotify]()
	register[BLEDisconnect]()
	register[PairStatus]()
	register[Reply]()
}

// ErrUnknownType is a line whose type this side does not know, which the protocol says to ignore.
var ErrUnknownType = errors.New("protocol: unknown type")

// Decode parses one line.
func Decode(line []byte) (Message, error) {
	var head struct {
		Type string `json:"type"`
	}
	if err := json.Unmarshal(line, &head); err != nil {
		return nil, fmt.Errorf("protocol: %w", err)
	}
	decode, ok := decoders[head.Type]
	if !ok {
		return nil, fmt.Errorf("%w %q", ErrUnknownType, head.Type)
	}
	m, err := decode(line)
	if err != nil {
		return nil, fmt.Errorf("protocol: %s: %w", head.Type, err)
	}
	return m, nil
}

// Encode renders m as one line, with its type first and no newline.
func Encode(m Message) ([]byte, error) {
	body, err := json.Marshal(m)
	if err != nil {
		return nil, err
	}
	head, err := json.Marshal(m.Type())
	if err != nil {
		return nil, err
	}
	var b bytes.Buffer
	b.WriteString(`{"type":`)
	b.Write(head)
	if rest := body[1:]; !bytes.Equal(rest, []byte("}")) {
		b.WriteByte(',')
		b.Write(rest)
	} else {
		b.WriteByte('}')
	}
	return b.Bytes(), nil
}
