// The online demo (GitHub Pages) has no server. Instead the real LekhaMint
// server, compiled to WebAssembly (server/cmd/lekhamint-wasm), runs in this
// tab on an in-memory database seeded with a sample household. API calls go to
// it instead of the network; nothing leaves the browser.

export const DEMO = import.meta.env.MODE === 'demo'
export const DEMO_EMAIL = 'demo@example.com'
export const DEMO_PASSWORD = 'demo1234'

interface DemoResponse { status: number; headers: Record<string, string>; body: string }
declare global {
  // eslint-disable-next-line no-var
  var lekhamintDemo: { fetch(method: string, path: string, headers: Record<string, string>, body?: string | Uint8Array): Promise<DemoResponse> } | undefined
  // eslint-disable-next-line no-var
  var lekhamintDemoReport: ((err: string | null) => void) | undefined
  // eslint-disable-next-line no-var
  var Go: new () => { importObject: WebAssembly.Imports; run(i: WebAssembly.Instance): Promise<void> }
}

let started: Promise<void> | null = null

function loadScript(src: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const s = document.createElement('script')
    s.src = src
    s.onload = () => resolve()
    s.onerror = () => reject(new Error(`Could not load ${src}`))
    document.head.appendChild(s)
  })
}

/** Downloads and starts the in-browser server once; later calls share the same start. */
export function startDemo(onProgress?: (fraction: number) => void): Promise<void> {
  started ??= (async () => {
    const base = import.meta.env.BASE_URL
    await loadScript(`${base}wasm_exec.js`)
    const ready = new Promise<void>((resolve, reject) => {
      globalThis.lekhamintDemoReport = (err) => (err ? reject(new Error(err)) : resolve())
    })
    const res = await fetch(`${base}lekhamint.wasm`)
    if (!res.ok || !res.body) throw new Error(`Could not download the demo (HTTP ${res.status})`)
    const total = Number(res.headers.get('Content-Length')) || 0
    const reader = res.body.getReader()
    const chunks: Uint8Array[] = []
    let got = 0
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      chunks.push(value)
      got += value.length
      // Content-Length is the compressed size when the host gzips, so cap the estimate.
      if (total) onProgress?.(Math.min(0.95, got / Math.max(total, got)))
    }
    const bytes = new Uint8Array(got)
    let at = 0
    for (const c of chunks) { bytes.set(c, at); at += c.length }
    const go = new globalThis.Go()
    const { instance } = await WebAssembly.instantiate(bytes, go.importObject)
    void go.run(instance)
    await ready
    onProgress?.(1)
  })()
  started.catch(() => { started = null })
  return started
}

const NULL_BODY = new Set([101, 204, 205, 304])

/** fetch() for the in-browser server: same contract as the real network call. */
export async function demoFetch(url: string, init: { method: string; headers: Record<string, string>; body?: BodyInit }): Promise<Response> {
  await startDemo()
  let body: string | Uint8Array | undefined
  if (typeof init.body === 'string') body = init.body
  else if (init.body instanceof Blob) body = new Uint8Array(await init.body.arrayBuffer())
  const r = await globalThis.lekhamintDemo!.fetch(init.method, url, init.headers, body)
  return new Response(NULL_BODY.has(r.status) ? null : r.body, { status: r.status, headers: r.headers })
}
