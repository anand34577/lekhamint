package syncer_test

import (
	"testing"
	"time"

	"github.com/anand34577/lekhamint/server/internal/ids"
	"github.com/anand34577/lekhamint/server/internal/syncer"
)

func TestPullWaitWakesOnChange(t *testing.T) {
	svc, _, lid := setup(t)
	dv := newDevice()
	done := make(chan syncer.PullResult, 1)
	go func() {
		res, err := svc.PullWait(ctx, lid, 0, 100, 10*time.Second)
		if err != nil {
			t.Error(err)
		}
		done <- res
	}()
	time.Sleep(100 * time.Millisecond)
	start := time.Now()
	if _, err := svc.Push(ctx, lid, []syncer.Change{dv.change("accounts", ids.NewV7(), account("Cash"))}); err != nil {
		t.Fatal(err)
	}
	select {
	case res := <-done:
		if len(res.Changes) != 1 || res.NextCursor != 1 {
			t.Fatalf("woken pull %+v", res)
		}
		if time.Since(start) > 2*time.Second {
			t.Fatal("pull was not woken by the push")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("pull did not return after a change")
	}
}

func TestPullWaitTimesOutEmpty(t *testing.T) {
	svc, _, lid := setup(t)
	start := time.Now()
	res, err := svc.PullWait(ctx, lid, 0, 100, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Changes) != 0 || time.Since(start) < 900*time.Millisecond {
		t.Fatalf("expected an empty answer after the wait, got %+v", res)
	}
}

func TestPullWaitAnswersAtOnceWhenBehind(t *testing.T) {
	svc, _, lid := setup(t)
	dv := newDevice()
	if _, err := svc.Push(ctx, lid, []syncer.Change{dv.change("accounts", ids.NewV7(), account("Bank"))}); err != nil {
		t.Fatal(err)
	}
	start := time.Now()
	res, err := svc.PullWait(ctx, lid, 0, 100, 10*time.Second)
	if err != nil || len(res.Changes) != 1 || time.Since(start) > time.Second {
		t.Fatalf("pull behind the feed should answer at once: %+v %v", res, err)
	}
}
