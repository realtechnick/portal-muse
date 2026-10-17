package main

import (
	"context"
	"net"
	"testing"
	"time"
)

func TestDialNameserverReachesEveryServer(t *testing.T) {
	var listeners []*net.UDPConn
	var servers []string
	for range 2 {
		l, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
		if err != nil {
			t.Fatal(err)
		}
		defer l.Close()
		listeners = append(listeners, l)
		servers = append(servers, l.LocalAddr().String())
	}
	setNameservers(servers)

	for range len(servers) {
		conn, err := dialNameserver(context.Background(), "udp", "127.0.0.1:53")
		if err != nil {
			t.Fatal(err)
		}
		if _, err := conn.Write([]byte{1}); err != nil {
			t.Fatal(err)
		}
		conn.Close()
	}

	for i, l := range listeners {
		_ = l.SetReadDeadline(time.Now().Add(time.Second))
		if _, _, err := l.ReadFromUDP(make([]byte, 1)); err != nil {
			t.Errorf("server %d never heard a query: %v", i, err)
		}
	}
}

func TestSetNameserversAddsPort53(t *testing.T) {
	setNameservers([]string{"10.0.0.1", "[2601::1]:5353", "fe80::1"})
	got := *nameservers.Load()
	want := []string{"10.0.0.1:53", "[2601::1]:5353", "[fe80::1]:53"}
	if len(got) != len(want) {
		t.Fatalf("got %q", got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("server %d = %q, want %q", i, got[i], want[i])
		}
	}
}
