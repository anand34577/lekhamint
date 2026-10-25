import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { App } from './App'
import { DEMO, startDemo } from './lib/demo'
import { session } from './lib/api'
import { installFormValidation } from './lib/validation'
import './styles.css'

installFormValidation()

const client = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 30_000, refetchOnWindowFocus: true, retry: (n, e) => n < 2 && !(e as { status?: number }).status },
  },
})

const root = createRoot(document.getElementById('root')!)
const render = () => root.render(
  <StrictMode>
    <QueryClientProvider client={client}>
      {DEMO && <DemoBanner />}
      <App />
    </QueryClientProvider>
  </StrictMode>,
)

function DemoBanner() {
  return (
    <div className="demo-banner" role="note">
      <span>
        <strong>Live demo.</strong> <span className="hide-sm">Sample data runs entirely in your browser; changes reset when you reload.</span>
        <span className="show-sm">Resets on reload.</span>{' '}
        <a href="https://github.com/anand34577/lekhamint#install" target="_blank" rel="noreferrer">Host your own →</a>
      </span>
    </div>
  )
}

if (DEMO) {
  // Every page load starts a fresh in-browser server, so an old sign-in is meaningless.
  session.token = null
  document.documentElement.classList.add('demo')
  const splash = (html: string) => root.render(<div className="splash" dangerouslySetInnerHTML={{ __html: html }} />)
  splash('<p>Starting the demo…</p>')
  startDemo((f) => splash(`<p>Starting the demo… ${Math.round(f * 100)}%</p><p class="muted small">Building a sample household with six months of history.</p>`))
    .then(render)
    .catch((e: Error) => splash(`<p>The demo could not start: ${e.message.replace(/[<>&]/g, '')}</p><p class="muted small">It needs a recent browser with WebAssembly. Reload to try again.</p>`))
} else {
  render()
  // Installable app and offline start-up (production builds only; dev uses Vite's server).
  if (import.meta.env.PROD && 'serviceWorker' in navigator) {
    addEventListener('load', () => { navigator.serviceWorker.register('/sw.js').catch(() => { /* not fatal */ }) })
  }
}
