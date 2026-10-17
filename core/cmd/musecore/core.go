package main

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"io"
	"io/fs"
	"log/slog"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/wobsoriano/muse-portal/core/internal/muse"
	"github.com/wobsoriano/muse-portal/core/internal/muse/pairing"
	"github.com/wobsoriano/muse-portal/core/internal/protocol"
)

// version is reported to Muse and to the phone while pairing. The build sets it.
var version = "dev"

// core is the event loop. run's goroutine owns every field below events; stdin, the Muse client
// and the pairing Controller reach it only through post and request.
type core struct {
	out      *writer
	events   chan event
	finished chan struct{}
	// root ends at shutdown, and with it the client, the pairing and any ask.
	root context.Context

	store muse.FileStore
	st    muse.State
	// loadErr is why the state file could not be read, until something is saved over it.
	loadErr error
	// cfg is nil until the app's first config.
	cfg    *protocol.Config
	client *clientRun
	// pair is nil while pairing is closed.
	pair *pairRun
	// asking is set while an ask is in flight: Muse does not say which question an answer
	// belongs to, so there is only ever one.
	asking  bool
	status  protocol.Status
	closing bool
}

// plan is what a client is built from: a config that changes any of it restarts the client.
type plan struct{ name, sdkToken, dns string }

func planOf(cfg *protocol.Config) plan {
	return plan{name: cfg.Name, sdkToken: cfg.SDKToken, dns: strings.Join(cfg.DNS, ",")}
}

// clientRun is one muse.Client from start until Run returns. Once stopping, nothing it says is
// reported and its saves are refused, so a token rotation racing an unpair cannot bring the
// credentials back. No new client starts until it has returned: one identity holds one session.
type clientRun struct {
	plan     plan
	c        *muse.Client
	stop     context.CancelFunc
	stopping bool
	state    muse.ConnState
}

// pairPhase is where the pairing link stands. A closed pairing is core.pair == nil.
type pairPhase int

const (
	// pairOpen: advertising, no phone on the link.
	pairOpen pairPhase = iota
	// pairConnected: a phone is on the link and its writes go to the Controller.
	pairConnected
	// pairFinished: the tokens are saved and the phone told; the service stays up for
	// pairing.ShutdownGrace so the last notification reaches it.
	pairFinished
)

type pairRun struct {
	ctrl   *pairing.Controller
	link   *bleLink
	cancel context.CancelFunc
	phase  pairPhase
	name   string
}

type event interface{}

type (
	inbound    struct{ msg protocol.Message }
	inputEnded struct{}
	clientSaid struct {
		run   *clientRun
		state muse.ConnState
	}
	clientSave struct {
		run   *clientRun
		st    muse.State
		reply chan error
	}
	clientDone struct {
		run *clientRun
		err error
	}
	pairProgress struct {
		run *pairRun
		p   pairing.Progress
	}
	pairSave struct {
		run    *pairRun
		result pairing.Result
		reply  chan error
	}
	pairDone struct {
		run *pairRun
		err error
	}
	askDone struct{}
)

var errStopping = errors.New("musecore is stopping")

// run serves the protocol on in and out until in ends or ctx does, and returns once nothing it
// started is still running.
func run(ctx context.Context, dataDir string, in io.Reader, out io.Writer) {
	root, cancel := context.WithCancel(context.Background())
	defer cancel()
	c := &core{
		out:      &writer{w: out},
		events:   make(chan event, 64),
		finished: make(chan struct{}),
		root:     root,
		store:    muse.FileStore{Path: filepath.Join(dataDir, "muse.json")},
	}
	defer close(c.finished)
	c.load()
	go c.read(in)

	done := ctx.Done()
	for !c.closing || c.client != nil || c.pair != nil || c.asking {
		select {
		case ev := <-c.events:
			c.handle(ev)
		case <-done:
			done = nil
			c.handle(inputEnded{})
		}
		if c.closing {
			cancel()
		}
	}
}

func (c *core) post(ev event) {
	select {
	case c.events <- ev:
	case <-c.finished:
	}
}

// request posts the event build makes around a reply channel, and waits for the loop's answer.
func (c *core) request(build func(reply chan error) event) error {
	reply := make(chan error, 1)
	c.post(build(reply))
	select {
	case err := <-reply:
		return err
	case <-c.finished:
		return errStopping
	}
}

func (c *core) read(in io.Reader) {
	r := bufio.NewReader(in)
	for {
		line, err := r.ReadBytes('\n')
		if line = bytes.TrimSpace(line); len(line) > 0 {
			msg, derr := protocol.Decode(line)
			switch {
			case errors.Is(derr, protocol.ErrUnknownType):
				slog.Info("ignoring a message", "err", derr)
			case derr != nil:
				slog.Warn("bad message", "err", derr)
			default:
				c.post(inbound{msg})
			}
		}
		if err != nil {
			if err != io.EOF {
				slog.Warn("stdin", "err", err)
			}
			c.post(inputEnded{})
			return
		}
	}
}

func (c *core) load() {
	st, err := c.store.Load()
	switch {
	case err == nil:
		c.st, c.loadErr = st, nil
	case errors.Is(err, fs.ErrNotExist):
		c.st, c.loadErr = muse.State{}, nil
	default:
		slog.Error("could not read the pairing", "err", err)
		c.loadErr = err
	}
}

func (c *core) save(st muse.State) error {
	if err := c.store.Save(st); err != nil {
		return err
	}
	c.st, c.loadErr = st, nil
	return nil
}

func (c *core) handle(ev event) {
	switch ev := ev.(type) {
	case inbound:
		if !c.closing {
			c.handleMessage(ev.msg)
		}
	case inputEnded:
		c.closing = true
		if c.pair != nil {
			c.pair.cancel()
		}
		c.reconcile()
	case clientSaid:
		if c.client == ev.run && !ev.run.stopping {
			ev.run.state = ev.state
		}
	case clientSave:
		if c.client != ev.run || ev.run.stopping {
			ev.reply <- errors.New("muse: the client is being stopped")
			break
		}
		ev.reply <- c.save(ev.st)
	case clientDone:
		if c.client == ev.run {
			c.client = nil
		}
		if errors.Is(ev.err, muse.ErrUnpaired) {
			c.load()
		}
		c.reconcile()
	case pairProgress:
		c.progressed(ev.run, ev.p)
	case pairSave:
		if c.pair != ev.run {
			ev.reply <- errors.New("pairing: this pairing was closed")
			break
		}
		ev.reply <- c.savePairing(ev.result)
	case pairDone:
		if c.pair == ev.run {
			c.pair = nil
		}
		c.out.send(protocol.PairUnadvertise{})
		if ev.err != nil {
			slog.Info("pairing closed", "err", ev.err)
			c.out.send(protocol.PairStatus{State: "closed", Name: ev.run.name})
		}
		c.reconcile()
	case askDone:
		c.asking = false
	}
	c.publishStatus(false)
}

func (c *core) handleMessage(msg protocol.Message) {
	slog.Debug("message", "type", msg.Type())
	switch m := msg.(type) {
	case protocol.Config:
		c.cfg = &m
		setNameservers(m.DNS)
		c.reconcile()
		c.publishStatus(true)
	case protocol.PairStart:
		c.startPairing()
	case protocol.PairStop:
		if c.pair != nil && c.pair.phase != pairFinished {
			c.pair.cancel()
		}
	case protocol.BLEAdvertising:
		if c.pair == nil || m.OK {
			break
		}
		c.out.send(protocol.PairStatus{State: "failed", Name: c.pair.name, Error: "could not advertise: " + m.Error})
		c.pair.cancel()
	case protocol.BLEConnected:
		if c.pair != nil && c.pair.phase == pairOpen {
			c.pair.phase = pairConnected
		}
	case protocol.BLEWrite:
		if c.pair == nil || c.pair.phase == pairFinished {
			slog.Info("ignoring a write with no pairing open")
			break
		}
		c.pair.phase = pairConnected
		c.pair.link.mtu.Store(int64(m.MTU))
		c.pair.ctrl.OnWrite(m.Data)
	case protocol.BLEDisconnected:
		if c.pair != nil && c.pair.phase == pairConnected {
			c.pair.phase = pairOpen
			c.pair.link.mtu.Store(0)
			c.pair.ctrl.OnDisconnect()
		}
	case protocol.Ask:
		c.ask(m)
	case protocol.Unpair:
		c.unpair()
	default:
		slog.Info("ignoring a message meant for the app", "type", msg.Type())
	}
}

// reconcile makes what runs match what should: a client exactly when the app has configured one,
// the device is paired, no pairing is open, and musecore is not stopping, built from the current
// config.
func (c *core) reconcile() {
	want := c.cfg != nil && c.st.Credentials != nil && c.pair == nil && !c.closing
	if r := c.client; r != nil && !r.stopping && (!want || r.plan != planOf(c.cfg)) {
		r.stopping = true
		r.stop()
	}
	if want && c.client == nil {
		c.startClient()
	}
}

func (c *core) startClient() {
	ctx, cancel := context.WithCancel(c.root)
	r := &clientRun{plan: planOf(c.cfg), stop: cancel, state: muse.ConnState{Phase: muse.Connecting}}
	client, err := muse.New(muse.Config{
		Store:       clientStore{c: c, run: r},
		DisplayName: c.cfg.Name,
		Version:     version,
		SDKToken:    c.cfg.SDKToken,
		Logger:      slog.Default(),
		OnState:     func(s muse.ConnState) { c.post(clientSaid{run: r, state: s}) },
	})
	if err != nil {
		cancel()
		slog.Error("could not make the Muse client", "err", err)
		return
	}
	r.c = client
	c.client = r
	go func() {
		err := client.Run(ctx)
		c.post(clientDone{run: r, err: err})
	}()
}

// publishStatus tells the app where the connection stands when that has changed, or always when
// force is set. Nothing is said before the first config.
func (c *core) publishStatus(force bool) {
	if c.cfg == nil {
		return
	}
	var s protocol.Status
	switch {
	case c.client != nil && !c.client.stopping:
		s.Phase = c.client.state.Phase.String()
		if c.client.state.Phase == muse.Offline && c.client.state.Err != nil {
			s.Error = c.client.state.Err.Error()
		}
	case c.loadErr != nil:
		s = protocol.Status{Phase: muse.Offline.String(), Error: "could not read the pairing: " + c.loadErr.Error()}
	case c.st.Credentials == nil:
		s.Phase = muse.Unpaired.String()
	default:
		s.Phase = muse.Offline.String()
	}
	if force || s != c.status {
		c.status = s
		c.out.send(s)
	}
}

func (c *core) startPairing() {
	if c.pair != nil {
		slog.Info("pairing is already open")
		return
	}
	failed := func(err string) {
		name := ""
		if c.st.Identity.Valid() {
			name = c.st.Identity.BLEName()
		}
		c.out.send(protocol.PairStatus{State: "failed", Name: name, Error: err})
	}
	if c.cfg == nil || c.cfg.SDKToken == "" {
		failed("no Muse SDK token: the Muse app will not pair without one")
		return
	}
	// The identity is on disk before it is advertised: the app remembers the device by it.
	if !c.st.Identity.Valid() {
		id, err := muse.NewIdentity()
		if err == nil {
			next := c.st
			next.Identity = id
			err = c.save(next)
		}
		if err != nil {
			failed("could not keep the device's identity: " + err.Error())
			return
		}
	}
	id := c.st.Identity
	run := &pairRun{link: &bleLink{out: c.out}, phase: pairOpen, name: id.BLEName()}
	ssid := c.cfg.SSID
	ctrl, err := pairing.New(pairing.Config{
		Device:    pairing.Device{NodeID: id.NodeID(), BLEName: id.BLEName(), MAC: id.MAC, Version: version},
		Transport: run.link,
		SDKToken:  c.cfg.SDKToken,
		Network:   pairing.AlreadyOnline{SSID: func() string { return ssid }},
		Save: func(r pairing.Result) error {
			return c.request(func(reply chan error) event { return pairSave{run: run, result: r, reply: reply} })
		},
		Progress: func(p pairing.Progress) { c.post(pairProgress{run: run, p: p}) },
	})
	if err != nil {
		failed(err.Error())
		return
	}
	ctx, cancel := context.WithCancel(c.root)
	run.ctrl, run.cancel = ctrl, cancel
	c.pair = run
	c.reconcile()
	c.out.send(protocol.PairAdvertise{
		Name:             run.name,
		Service:          pairing.ServiceUUID,
		RX:               pairing.RXUUID,
		TX:               pairing.TXUUID,
		ManufacturerID:   int(pairing.ManufacturerID),
		ManufacturerData: []byte{pairing.ManufacturerData},
	})
	slog.Info("pairing open", "name", run.name, "for", pairing.DefaultWindow)
	go func() {
		err := ctrl.Run(ctx)
		if err == nil {
			select {
			case <-time.After(pairing.ShutdownGrace):
			case <-ctx.Done():
			}
		}
		cancel()
		c.post(pairDone{run: run, err: err})
	}()
}

func (c *core) progressed(run *pairRun, p pairing.Progress) {
	if c.pair != run {
		return
	}
	s := protocol.PairStatus{Name: run.name}
	switch p.State {
	case pairing.StateWaiting:
		s.State = "open"
	case pairing.StateConnected, pairing.StateConfirmed, pairing.StateProvisioning:
		s.State = "connected"
	case pairing.StateDone:
		run.phase = pairFinished
		s.State = "paired"
	case pairing.StateFailed:
		// A stop is reported as closed once the Controller has returned.
		if p.Reason == pairing.ReasonCanceled {
			return
		}
		s.State, s.Error = "failed", p.Reason.String()
		if p.Err != nil {
			s.Error += ": " + p.Err.Error()
		}
	}
	c.out.send(s)
}

// savePairing is the Controller's Save: the tokens the app sent, stamped the way the SDK stamps
// them.
func (c *core) savePairing(r pairing.Result) error {
	st := c.st
	st.Credentials = &muse.Credentials{
		AccessToken:        r.AccessToken,
		RefreshToken:       r.RefreshToken,
		TokenType:          r.TokenType,
		Username:           r.Username,
		APIURL:             r.APIURL,
		APIURLV2:           r.APIURLV2,
		NoiseHost:          r.NoiseHost,
		AccessTokenSavedAt: time.Now().Unix(),
	}
	// A fresh pairing has told Muse nothing yet.
	st.SDKTokenReported = false
	return c.save(st)
}

// unpair forgets the tokens and keeps the identity, so the device pairs again as itself.
func (c *core) unpair() {
	if c.pair != nil && c.pair.phase != pairFinished {
		c.pair.cancel()
	}
	if err := c.save(muse.State{Identity: c.st.Identity}); err != nil {
		slog.Error("could not forget the pairing", "err", err)
		return
	}
	slog.Info("unpaired")
	c.reconcile()
}

func (c *core) ask(a protocol.Ask) {
	fail := func(err error) {
		c.out.send(protocol.Reply{ID: a.ID, Kind: "error", Error: err.Error()})
	}
	var in muse.AskInput
	switch {
	case a.Text != nil && a.WAV == nil:
		in = muse.Text(*a.Text)
	case a.WAV != nil && a.Text == nil:
		in = muse.VoiceNote(a.WAV)
	default:
		fail(errors.New("an ask carries exactly one of text and wav"))
		return
	}
	if c.asking {
		fail(muse.ErrBusy)
		return
	}
	if c.client == nil || c.client.stopping {
		fail(muse.ErrOffline)
		return
	}
	client, id := c.client.c, a.ID
	c.asking = true
	go func() {
		reply, err := client.Ask(c.root, in, func(e muse.ReplyEvent) {
			switch e := e.(type) {
			case muse.Heard:
				c.out.send(protocol.Reply{ID: id, Kind: "heard", Text: e.Text})
			case muse.ReplyText:
				c.out.send(protocol.Reply{ID: id, Kind: "text", Text: e.Text})
			case muse.Settled:
				c.out.send(protocol.Reply{ID: id, Kind: "settled", Text: e.Text})
			}
		})
		if err != nil {
			c.out.send(protocol.Reply{ID: id, Kind: "error", Error: err.Error()})
		} else {
			c.out.send(protocol.Reply{ID: id, Kind: "done", Text: reply.Text})
		}
		c.post(askDone{})
	}()
}

// clientStore is a client's view of the state file. Reads go to the file; writes go through the
// loop, which refuses them once the client is being stopped.
type clientStore struct {
	c   *core
	run *clientRun
}

func (s clientStore) Load() (muse.State, error) { return s.c.store.Load() }

func (s clientStore) Save(st muse.State) error {
	return s.c.request(func(reply chan error) event { return clientSave{run: s.run, st: st, reply: reply} })
}

// writer is the one way to stdout, so lines from the loop, the Controller and an ask never mix.
type writer struct {
	mu sync.Mutex
	w  io.Writer
}

func (w *writer) send(m protocol.Message) {
	b, err := protocol.Encode(m)
	if err != nil {
		slog.Error("could not encode a message", "type", m.Type(), "err", err)
		return
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	if _, err := w.w.Write(append(b, '\n')); err != nil {
		slog.Warn("stdout", "err", err)
	}
}
