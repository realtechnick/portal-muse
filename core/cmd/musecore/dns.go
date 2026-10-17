package main

import (
	"context"
	"errors"
	"net"
	"sync/atomic"
)

// nameservers are the DNS servers the app reported. A static Linux binary on Android finds no
// /etc/resolv.conf and would otherwise ask 127.0.0.1, where nothing answers.
var nameservers atomic.Pointer[[]string]

// installResolver is called once, before anything resolves: net.DefaultResolver is read without a
// lock, so the servers change through nameservers rather than by replacing it.
func installResolver() {
	net.DefaultResolver = &net.Resolver{PreferGo: true, Dial: dialNameserver}
}

func setNameservers(servers []string) {
	var list []string
	for _, s := range servers {
		if _, _, err := net.SplitHostPort(s); err != nil {
			s = net.JoinHostPort(s, "53")
		}
		list = append(list, s)
	}
	nameservers.Store(&list)
}

// nextServer rotates the starting server. A UDP dial succeeds whether or not anyone answers, so
// trying servers in order within one dial never falls back; the resolver's own retries, each a
// fresh dial, do once every dial starts at the next server.
var nextServer atomic.Uint32

func dialNameserver(ctx context.Context, network, address string) (net.Conn, error) {
	var d net.Dialer
	servers := nameservers.Load()
	if servers == nil || len(*servers) == 0 {
		return d.DialContext(ctx, network, address)
	}
	list := *servers
	start := int(nextServer.Add(1)-1) % len(list)
	var errs []error
	for i := range list {
		conn, err := d.DialContext(ctx, network, list[(start+i)%len(list)])
		if err == nil {
			return conn, nil
		}
		errs = append(errs, err)
	}
	return nil, errors.Join(errs...)
}
