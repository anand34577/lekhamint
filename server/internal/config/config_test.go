package config

import (
	"flag"
	"strings"
	"testing"
)

func parse(t *testing.T, env map[string]string, args ...string) error {
	t.Helper()
	for k, v := range env {
		t.Setenv(k, v)
	}
	fs := flag.NewFlagSet("test", flag.ContinueOnError)
	c := Register(fs)
	if err := fs.Parse(args); err != nil {
		return err
	}
	return c.Finish()
}

func TestDefaultsAreValid(t *testing.T) {
	if err := parse(t, nil); err != nil {
		t.Fatal(err)
	}
}

// A typo in a setting stops the server with every problem listed, instead of
// quietly falling back to a default.
func TestBadSettingsAreReported(t *testing.T) {
	err := parse(t, map[string]string{
		"LEKHAMINT_ALLOW_SIGNUP":    "yes please",
		"LEKHAMINT_BACKUP_INTERVAL": "daily",
		"LEKHAMINT_LOG_LEVEL":       "loud",
		"LEKHAMINT_ADDR":            "8080",
		"LEKHAMINT_DB_DRIVER":       "postgres",
	})
	if err == nil {
		t.Fatal("expected an error")
	}
	for _, want := range []string{"LEKHAMINT_ALLOW_SIGNUP", "LEKHAMINT_BACKUP_INTERVAL", "LEKHAMINT_LOG_LEVEL", "LEKHAMINT_ADDR", "LEKHAMINT_DB_DSN"} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("error does not mention %s:\n%v", want, err)
		}
	}
}

func TestZeroBackupIntervalDisables(t *testing.T) {
	if err := parse(t, map[string]string{"LEKHAMINT_BACKUP_INTERVAL": "0"}); err != nil {
		t.Fatal(err)
	}
}
