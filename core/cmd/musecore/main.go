// Command musecore is the Muse half of Muse for Portal: the app starts it, and they talk over its
// stdin and stdout as PROTOCOL.md says. Logs go to stderr.
package main

import (
	"context"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
)

func main() {
	data := flag.String("data", "", "the app's private files directory, where the pairing is kept")
	debug := flag.Bool("debug", false, "log at debug level")
	flag.Parse()
	if *data == "" {
		fmt.Fprintln(os.Stderr, "usage: musecore --data <dir>")
		os.Exit(2)
	}
	level := slog.LevelInfo
	if *debug {
		level = slog.LevelDebug
	}
	slog.SetDefault(slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: level})))
	installResolver()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	slog.Info("musecore starting", "version", version)
	run(ctx, *data, os.Stdin, os.Stdout)
}
