package main

import (
	"sync/atomic"

	"github.com/wobsoriano/muse-portal/core/internal/muse/pairing"
	"github.com/wobsoriano/muse-portal/core/internal/protocol"
)

// bleLink is the pairing.Transport whose GATT server is the app's. The Controller calls it from its
// own goroutine, so the MTU the loop learns from ble.write is kept atomically.
type bleLink struct {
	out *writer
	mtu atomic.Int64
}

func (l *bleLink) Send(packets [][]byte) error {
	l.out.send(protocol.BLENotify{Packets: packets})
	return nil
}

func (l *bleLink) Disconnect() error {
	l.out.send(protocol.BLEDisconnect{})
	return nil
}

func (l *bleLink) MTU() int {
	if m := l.mtu.Load(); m > 0 {
		return int(m)
	}
	return pairing.AssumedMTU
}
