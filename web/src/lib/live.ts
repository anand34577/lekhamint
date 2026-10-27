// Live updates: while this tab is open and visible, a request waits on the server for the book
// to change (a phone syncing, another browser saving) and the page refreshes its data when it does.
// Hidden tabs stop waiting; coming back checks at once.

import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { ApiError, api } from './api'
import { DEMO } from './demo'

export function useLiveUpdates(ledgerId: string, startSeq: number) {
  const qc = useQueryClient()
  useEffect(() => {
    if (DEMO) return // the demo server runs in this tab; nothing else writes to it
    let stopped = false
    let cursor = startSeq
    let ctrl: AbortController | null = null
    let wake: () => void = () => {}
    const sleep = (ms: number) => new Promise<void>((done) => {
      const t = setTimeout(done, ms)
      wake = () => { clearTimeout(t); done() }
    })
    // This tab's own saves already refresh what they change; skip the echo of them.
    let lastLocalSave = 0
    const unsubscribe = qc.getMutationCache().subscribe((e) => {
      if (e.type === 'updated' && e.mutation.state.status === 'success') lastLocalSave = Date.now()
    })
    const awake = () => document.visibilityState === 'visible' && navigator.onLine
    const head = async () => (await api.ledgers()).find((l) => l.id === ledgerId)?.seq ?? cursor

    const loop = async () => {
      let backoff = 5_000
      while (!stopped) {
        if (!awake()) { await sleep(5 * 60_000); continue }
        ctrl = new AbortController()
        const started = Date.now()
        try {
          const r = await api.waitForChange(ledgerId, cursor, ctrl.signal)
          backoff = 5_000
          if (r.changes.length > 0 || r.next_cursor > cursor) {
            cursor = r.has_more ? await head() : r.next_cursor
            await sleep(400) // let a burst of changes land, then refresh once
            if (!stopped && Date.now() - lastLocalSave > 2_000) qc.invalidateQueries()
          } else if (Date.now() - started < 3_000) {
            await sleep(20_000) // a server without waiting pulls: check every 20 seconds
          }
        } catch (e) {
          if (stopped) return
          if (e instanceof DOMException && e.name === 'AbortError') continue
          // The position may be too old (410) or the book changed hands: start again from now.
          if (e instanceof ApiError && e.status >= 400 && e.status < 500) { try { cursor = await head() } catch { /* offline */ } }
          await sleep(backoff)
          backoff = Math.min(backoff * 2, 60_000)
        }
      }
    }
    const onWake = () => {
      if (awake()) wake()
      else ctrl?.abort() // hidden or offline: free the connection
    }
    document.addEventListener('visibilitychange', onWake)
    addEventListener('online', onWake)
    addEventListener('offline', onWake)
    void loop()
    return () => {
      stopped = true
      ctrl?.abort(); wake()
      unsubscribe()
      document.removeEventListener('visibilitychange', onWake)
      removeEventListener('online', onWake)
      removeEventListener('offline', onWake)
    }
  }, [ledgerId, qc]) // eslint-disable-line react-hooks/exhaustive-deps -- startSeq is only where to begin
}
