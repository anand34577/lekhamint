import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// `npm run dev` proxies the API to a local server on :8080, so the browser
// sees one origin (same as production, where the Go binary serves this app).
export default defineConfig({
  plugins: [react()],
  server: { proxy: { '/api': 'http://localhost:8080', '/healthz': 'http://localhost:8080' } },
  build: { outDir: 'dist', sourcemap: false, chunkSizeWarningLimit: 600 },
})
