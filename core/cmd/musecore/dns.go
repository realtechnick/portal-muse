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

func dialNameserver(ctx context.Context, network, address string) (net.Conn, error) {
	var d net.Dialer
	servers := nameservers.Load()
	if servers == nil || len(*servers) == 0 {
		return d.DialContext(ctx, network, address)
	}
	var errs []error
	for _, s := range *servers {
		conn, err := d.DialContext(ctx, network, s)
		if err == nil {
			return conn, nil
		}
		errs = append(errs, err)
	}
	return nil, errors.Join(errs...)
}
