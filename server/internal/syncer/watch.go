package syncer

import (
	"context"
	"sync"
	"time"
)

// MaxPullWait is the longest a pull may wait for a new change. It stays well
// under the 60 s read timeout common in reverse proxies.
const MaxPullWait = 25 * time.Second

// watchers wakes pulls that are waiting on a ledger when that ledger changes.
// It only knows about writes made by this process; with several server
// processes a waiting pull still returns at its deadline, so devices catch up
// within MaxPullWait.
type watchers struct {
	mu   sync.Mutex
	subs map[string]chan struct{}
}

// wait returns a channel that is closed on the next change to ledgerID.
func (w *watchers) wait(ledgerID string) <-chan struct{} {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.subs == nil {
		w.subs = map[string]chan struct{}{}
	}
	ch, ok := w.subs[ledgerID]
	if !ok {
		ch = make(chan struct{})
		w.subs[ledgerID] = ch
	}
	return ch
}

// changed wakes everyone waiting on ledgerID.
func (w *watchers) changed(ledgerID string) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if ch, ok := w.subs[ledgerID]; ok {
		close(ch)
		delete(w.subs, ledgerID)
	}
}

// PullWait is Pull that, when there is nothing after since, waits up to wait
// for a change before answering. A zero wait is a plain Pull.
func (s *Service) PullWait(ctx context.Context, ledgerID string, since int64, limit int, wait time.Duration) (PullResult, error) {
	if wait > MaxPullWait {
		wait = MaxPullWait
	}
	if wait <= 0 {
		return s.Pull(ctx, ledgerID, since, limit)
	}
	// Subscribe before reading, so a write that lands between the read and
	// the wait still wakes us.
	woke := s.watch.wait(ledgerID)
	res, err := s.Pull(ctx, ledgerID, since, limit)
	if err != nil || len(res.Changes) > 0 || res.NextCursor > since {
		return res, err
	}
	t := time.NewTimer(wait)
	defer t.Stop()
	select {
	case <-woke:
		return s.Pull(ctx, ledgerID, since, limit)
	case <-t.C:
		return res, nil
	case <-ctx.Done():
		return res, nil
	}
}
