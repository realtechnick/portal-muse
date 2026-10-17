package protocol

import (
	"errors"
	"reflect"
	"testing"
)

func ptr[T any](v T) *T { return &v }

func TestEveryMessageRoundTrips(t *testing.T) {
	cases := []struct {
		msg  Message
		line string
	}{
		{Config{Name: "Kitchen", SDKToken: "mgst_x", SSID: "home", DNS: []string{"1.1.1.1", "8.8.8.8:53"}},
			`{"type":"config","name":"Kitchen","sdkToken":"mgst_x","ssid":"home","dns":["1.1.1.1","8.8.8.8:53"]}`},
		{PairStart{}, `{"type":"pair.start"}`},
		{PairStop{}, `{"type":"pair.stop"}`},
		{BLEAdvertising{OK: false, Error: "busy"}, `{"type":"ble.advertising","ok":false,"error":"busy"}`},
		{BLEConnected{Device: "AA:BB"}, `{"type":"ble.connected","device":"AA:BB"}`},
		{BLEWrite{Data: []byte{1, 2, 3}, MTU: 185}, `{"type":"ble.write","data":"AQID","mtu":185}`},
		{BLEDisconnected{}, `{"type":"ble.disconnected"}`},
		{Ask{ID: "1", Text: ptr("hi")}, `{"type":"ask","id":"1","text":"hi"}`},
		{Ask{ID: "2", WAV: []byte("RIFF")}, `{"type":"ask","id":"2","wav":"UklGRg=="}`},
		{Unpair{}, `{"type":"unpair"}`},
		{Status{Phase: "offline", Error: "no route"}, `{"type":"status","phase":"offline","error":"no route"}`},
		{PairAdvertise{Name: "MuseGadgetA1B2C3", Service: "s", RX: "r", TX: "t", ManufacturerID: 65535, ManufacturerData: []byte{0}},
			`{"type":"pair.advertise","name":"MuseGadgetA1B2C3","service":"s","rx":"r","tx":"t","manufacturerId":65535,"manufacturerData":"AA=="}`},
		{PairUnadvertise{}, `{"type":"pair.unadvertise"}`},
		{BLENotify{Packets: [][]byte{{0xff}, {0, 1}}}, `{"type":"ble.notify","packets":["/w==","AAE="]}`},
		{BLEDisconnect{}, `{"type":"ble.disconnect"}`},
		{PairStatus{State: "open", Name: "MuseGadgetA1B2C3"}, `{"type":"pair.status","state":"open","name":"MuseGadgetA1B2C3","error":""}`},
		{Reply{ID: "1", Kind: "done", Text: "Hello."}, `{"type":"reply","id":"1","kind":"done","text":"Hello.","error":""}`},
	}
	for _, c := range cases {
		t.Run(c.msg.Type(), func(t *testing.T) {
			b, err := Encode(c.msg)
			if err != nil {
				t.Fatal(err)
			}
			if string(b) != c.line {
				t.Fatalf("encoded\n got %s\nwant %s", b, c.line)
			}
			back, err := Decode(b)
			if err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(back, c.msg) {
				t.Fatalf("decoded %#v, want %#v", back, c.msg)
			}
		})
	}
}

func TestUnknownTypeIsReportedAsSuch(t *testing.T) {
	_, err := Decode([]byte(`{"type":"audio","pcm":"AAAA"}`))
	if !errors.Is(err, ErrUnknownType) {
		t.Fatalf("got %v, want ErrUnknownType", err)
	}
}

func TestAskTellsAnAbsentFieldFromAnEmptyOne(t *testing.T) {
	m, err := Decode([]byte(`{"type":"ask","id":"1","text":"","wav":""}`))
	if err != nil {
		t.Fatal(err)
	}
	a := m.(Ask)
	if a.Text == nil || a.WAV == nil {
		t.Fatalf("empty fields decoded as absent: %#v", a)
	}
	m, _ = Decode([]byte(`{"type":"ask","id":"1"}`))
	if a := m.(Ask); a.Text != nil || a.WAV != nil {
		t.Fatalf("absent fields decoded as present: %#v", a)
	}
}

func TestMalformedLine(t *testing.T) {
	if _, err := Decode([]byte(`{"type":"ble.write","data":"not base64!"}`)); err == nil || errors.Is(err, ErrUnknownType) {
		t.Fatalf("got %v, want a decoding error", err)
	}
}
